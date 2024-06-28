/*
    Licensed to the Apache Software Foundation (ASF) under one
    or more contributor license agreements.  See the NOTICE file
    distributed with this work for additional information
    regarding copyright ownership.  The ASF licenses this file
    to you under the Apache License, Version 2.0 (the
    "License"); you may not use this file except in compliance
    with the License.  You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing,
    software distributed under the License is distributed on an
    "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
    KIND, either express or implied.  See the License for the
    specific language governing permissions and limitations
    under the License.    
 */
package org.apache.wiki.search.kendra;

import software.amazon.awssdk.services.kendra.KendraClient;
import software.amazon.awssdk.services.kendra.KendraClientBuilder;
import software.amazon.awssdk.services.kendra.model.*;
import com.amazonaws.util.IOUtils;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.wiki.InternalWikiException;
import org.apache.wiki.WatchDog;
import org.apache.wiki.WikiBackgroundThread;
import org.apache.wiki.api.core.Attachment;
import org.apache.wiki.api.core.Context;
import org.apache.wiki.api.core.Engine;
import org.apache.wiki.api.core.Page;
import org.apache.wiki.api.exceptions.NoRequiredPropertyException;
import org.apache.wiki.api.exceptions.ProviderException;
import org.apache.wiki.api.providers.PageProvider;
import org.apache.wiki.api.search.SearchResult;
import org.apache.wiki.api.spi.Wiki;
import org.apache.wiki.attachment.AttachmentManager;
import org.apache.wiki.auth.AuthorizationManager;
import org.apache.wiki.auth.permissions.PagePermission;
import org.apache.wiki.pages.PageManager;
import org.apache.wiki.search.SearchProvider;
import org.apache.wiki.util.TextUtil;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static java.lang.String.format;

/**
 * Search provider that implements {link SearchProvider} using AWS Kendra for
 * indexing. Note that we are using a Custom DataSource which limits the
 * attributes that can be uploaded / searched for each page (as per
 * https://docs.aws.amazon.com/kendra/latest/dg/custom-attributes.html). This
 * could be overcome by using an S3 bucket where any custom attributes can be
 * added.
 *
 * @since 2.11.0
 */
public class KendraSearchProvider implements SearchProvider {

    private static final Logger LOG = LogManager.getLogger( KendraSearchProvider.class );
    private Engine engine;
    private Properties properties;
    private Map< String, Object > contentTypes;
    private KendraClient kendra;
    private String indexName;
    private String indexId;
    private String dataSourceName;
    private String dataSourceId;

    private final List< Page > updates = Collections.synchronizedList( new ArrayList<>() );

    private static final String PROP_KENDRA_INDEX_NAME = "jspwiki.kendra.indexName";
    private static final String PROP_KENDRA_DATA_SOURCE_NAME = "jspwiki.kendra.dataSourceName";
    private static final String PROP_KENDRA_INDEXDELAY = "jspwiki.kendra.indexdelay";
    private static final String PROP_KENDRA_INITIALDELAY = "jspwiki.kendra.initialdelay";

    public KendraSearchProvider() {
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void initialize( final Engine engine, final Properties properties ) throws NoRequiredPropertyException, IOException {
        this.engine = engine;
        this.properties = properties;
        this.contentTypes = getContentTypes();

        setKendra( buildClient() );

        this.indexName = TextUtil.getRequiredProperty( this.properties, PROP_KENDRA_INDEX_NAME );
        this.dataSourceName = TextUtil.getRequiredProperty( this.properties, PROP_KENDRA_DATA_SOURCE_NAME );
        final int initialDelay = TextUtil.getIntegerProperty( this.properties, PROP_KENDRA_INITIALDELAY, KendraUpdater.INITIAL_DELAY );
        final int indexDelay = TextUtil.getIntegerProperty( this.properties, PROP_KENDRA_INDEXDELAY, KendraUpdater.INDEX_DELAY );

        // Start the Kendra update thread, which waits first for a little while
        // before starting to go through the "pages that need updating".
        if ( initialDelay >= 0 ) {
            final KendraUpdater updater = new KendraUpdater( engine, this, initialDelay, indexDelay );
            updater.start();
        }
    }

    private Map< String, Object > getContentTypes() {
        final Gson gson = new GsonBuilder().create();
        try ( final InputStream in = KendraSearchProvider.class.getResourceAsStream( "content_types.json" ) ) {
            if ( in != null ) {
                final Type collectionType = new TypeToken< HashMap< String, Object > >() {
                }.getType();
                return gson.fromJson( new InputStreamReader( in ), collectionType );
            }
        } catch ( final IOException e ) {
            LOG.error( format( "Unable to load default propertyfile 'content_types.json': %s", e.getMessage() ), e );
        }
        return null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getProviderInfo() {
        return "KendraSearchProvider";
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void pageRemoved( final Page page ) {
        final String pageName = page.getName();
        final BatchDeleteDocumentRequest request = BatchDeleteDocumentRequest.builder().indexId( indexId )
                .documentIdList( pageName ).build();
        final BatchDeleteDocumentResponse result = getKendra().batchDeleteDocument( request );
        if (result.failedDocuments().isEmpty()) {
            LOG.debug( format( "Page '%s' was removed from index", pageName ) );
        } else {
            LOG.error( format( "Failed to remove Page '%s' from index", pageName ) );
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void reindexPage( final Page page ) {
        if ( page != null ) {
            updates.add( page );
            LOG.debug( format( "Scheduling page '%s' for indexing ...", page.getName() ) );
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Collection< SearchResult > findPages( final String query, final Context wikiContext ) throws ProviderException, IOException {
        final QueryRequest request = QueryRequest.builder().indexId( indexId ).queryText( query ).build();
        final List< QueryResultItem > items;
        try {
            items = getKendra().query( request ).getResultItems();
        } catch ( final ThrottlingException e ) {
            LOG.error( "ThrottlingException. Skipping..." );
            return new ArrayList<>();
        }
        final List< SearchResult > searchResults = new ArrayList<>( items.size() );
        final AuthorizationManager mgr = engine.getManager( AuthorizationManager.class );

        for ( final QueryResultItem item : items ) {
            switch( QueryResultType.fromValue( item.type() ) ) {
                case DOCUMENT:
                    final String documentId = item.documentId();
                    final String documentExcerpt = item.documentExcerpt().text();
                    final String scoreConfidence = item.scoreAttributes().scoreConfidence();
                    final Page page = this.engine.getManager( PageManager.class ).getPage( documentId, PageProvider.LATEST_VERSION );
                    if ( page != null ) {
                        final PagePermission pp = new PagePermission( page, PagePermission.VIEW_ACTION );
                        if ( mgr.checkPermission( wikiContext.getWikiSession(), pp ) ) {
                            final SearchResult searchResult = new SearchResultImpl( page, confidence2score( scoreConfidence ),
                                    new String[]{ documentExcerpt } );
                            searchResults.add( searchResult );
                        } else {
                            LOG.error( format( "Page '%s' is not accessible", documentId ) );
                        }
                    } else {
                        LOG.error(
                                format( "Kendra found a result page '%s' that could not be loaded, removing from index", documentId ) );
                        pageRemoved( Wiki.contents().page( this.engine, documentId ) );
                    }
                    break;
                default:
                    LOG.error( format( "Unknown query result type: %s", item.type() ) );
            }
        }
        return searchResults;
    }

    /**
     * This method initialize the AWS Kendra Index and Datasources to be used.
     */
    public void initializeIndexAndDataSource() {
        this.indexId = getIndexId( indexName );
        if ( this.indexId == null ) {
            final String message = format( "Index '%s' does not exist", indexName );
            LOG.error( message );
            throw new IllegalArgumentException( message );
        }
        this.dataSourceId = getDatasourceId( this.indexId, dataSourceName );
        if ( this.dataSourceId == null ) {
            final String message = format( "Datasource '%s' does not exist in index %s", dataSourceName, indexName );
            LOG.error( message );
            throw new IllegalArgumentException( message );
        }
    }

    /**
     * Given an Kendra's Index name, returns the corresponding Index Id, or
     * {@code null} if it does not exists
     *
     * @param indexName the name of the index to look up
     * @return the index id or {@code null}
     */
    private String getIndexId( final String indexName ) {
        ListIndicesRequest request = ListIndicesRequest.builder().build();
        ListIndicesResponse result = getKendra().listIndices( request );
        String nextToken = "";
        while ( nextToken != null ) {
            final List< IndexConfigurationSummary > items = result.indexConfigurationSummaryItems();
            if ( items == null || items.isEmpty() ) {
                return null;
            }
            for ( final IndexConfigurationSummary item : items ) {
                if ( StringUtils.equals( item.name(), indexName ) ) {
                    return item.id();
                }
            }
            nextToken = result.nextToken();
            request = ListIndicesRequest.builder().nextToken( result.nextToken() ).build();
            result = getKendra().listIndices( request );
        }
        return null;
    }

    /**
     * Given an Kendra's Datasource name, returns the corresponding Datasource Id,
     * or {@code null} if it does not exists
     *
     * @param dataSourceName the name of the datasource to look up
     * @return the datasource id or {@code null}
     */
    private String getDatasourceId( final String indexId, final String dataSourceName ) {
        ListDataSourcesRequest request = ListDataSourcesRequest.builder().indexId( indexId ).build();
        ListDataSourcesResponse result = getKendra().listDataSources( request );
        String nextToken = "";
        while ( nextToken != null ) {
            final List< DataSourceSummary > items = result.summaryItems();
            if ( items == null || items.isEmpty() ) {
                return null;
            }

            for ( final DataSourceSummary item : items ) {
                if ( StringUtils.equals( item.name(), dataSourceName ) ) {
                    return item.id();
                }
            }
            nextToken = result.nextToken();
            request = ListDataSourcesRequest.builder().nextToken( result.nextToken() ).build();
            result = getKendra().listDataSources( request );
        }
        return null;
    }

    /*
     * Converts a SCORE Confidence from Kendra to an "equivalent" integer score
     */
    private int confidence2score( final String scoreConfidence ) {
        switch ( ScoreConfidence.fromValue( scoreConfidence ) ) {
            case VERY_HIGH:
                return 100;
            case HIGH:
                return 75;
            case MEDIUM:
                return 50;
            case LOW:
                return 25;
            default:
                return 0;
        }
    }

    /**
     * This method re-index all the pages found in the Wiki. It is mainly used at
     * startup.
     *
     * @throws IOException in case some page can not be read
     */
    private void doFullReindex() throws IOException {
        try {
            final Collection< Page > pages = engine.getManager( PageManager.class ).getAllPages();
            if ( pages.isEmpty() ) {
                return;
            }
            LOG.debug( format( "Indexing all %d pages. Please wait ...", pages.size() ) );
            final String executionId = startExecution();
            for ( final Page page : pages ) {
                // Since I do not want to handle the size limit
                // (https://docs.aws.amazon.com/goto/WebAPI/kendra-2019-02-03/BatchPutDocument)
                // uploading documents one at a time
                indexOnePage( page, executionId );
            }
        } catch ( final ProviderException e ) {
            LOG.error( e.getMessage() );
            throw new IOException( e );
        } finally {
            stopExecution();
        }
    }

    /**
     * This method re-index all pages marked as updated. It is used to periodically
     * index pages that have been modified
     */
    private void doPartialReindex() {
        if ( updates.isEmpty() ) {
            return;
        }
        LOG.debug( "Indexing updated pages. Please wait ..." );
        final String executionId = startExecution();
        synchronized ( updates ) {
            try {
                while (!updates.isEmpty()) {
                    indexOnePage( updates.remove( 0 ), executionId );
                }
            } finally {
                stopExecution();
            }
        }
    }

    /**
     * Returns an ExecutiuonId that is required to keep track of the modifed
     * documents
     *
     * @return The execution id
     */
    private String startExecution() {
        final StartDataSourceSyncJobRequest request = StartDataSourceSyncJobRequest.builder().indexId( indexId )
                .id( dataSourceId ).build();
        final StartDataSourceSyncJobResponse result = getKendra().startDataSourceSyncJob( request );
        return result.executionId();
    }

    /**
     * Stop the execution for the given index Id and DataSource Id.
     */
    private void stopExecution() {
        final StopDataSourceSyncJobRequest request = StopDataSourceSyncJobRequest.builder().indexId( indexId ).id( dataSourceId ).build();
        getKendra().stopDataSourceSyncJob( request );
    }

    /**
     * Index on single {@link Page} into the Kendra Index
     *
     * @param page        the {@link Page} to index
     * @param executionId The Execution Id
     */
    private void indexOnePage( final Page page, final String executionId ) {
        final String pageName = page.getName();
        try {
            final Document document = newDocument( page, executionId );
            final BatchPutDocumentRequest request = BatchPutDocumentRequest.builder().indexId( indexId )
                    .documents( document ).build();
            final BatchPutDocumentResponse result = getKendra().batchPutDocument( request );
            if (result.failedDocuments().isEmpty()) {
                LOG.info( format( "Successfully indexed Page '%s' as %s", page.getName(), document.contentType() ) );
            } else {
                for ( final BatchPutDocumentResponseFailedDocument failedDocument : result.failedDocuments() ) {
                    LOG.error( format( "Failed to index Page '%s': %s", failedDocument.id(), failedDocument.errorMessage() ) );
                }
            }
        } catch ( final IOException e ) {
            LOG.error( format( "Failed to index Page '%s': %s", pageName, e.getMessage() ) );
        }
    }


    /**
     * Given a {@link Page}, returns the corresponding Kendra {@link Document}.
     *
     * @param page        the {@link Page} to be indexed
     * @param executionId an execution id to identify when the {@link Page} was
     *                    indexed for the last time.
     * @return a {@link Document} containing the searchable attributes.
     * @throws IOException if the {@link Page}'s {@link Attachment} can not be read.
     */
    private Document newDocument( final Page page, final String executionId ) throws IOException {
        final String pageName = page.getName();
        final List< DocumentAttribute > attrs = new ArrayList<>();
        // These 2 are required as per
        // https://docs.aws.amazon.com/kendra/latest/dg/data-source-custom.html#custom-required-attributes
        attrs.add( newAttribute( "_data_source_id", dataSourceId ) );
        attrs.add( newAttribute( "_data_source_sync_job_execution_id", executionId ) );

        final String title = TextUtil.beautifyString( pageName );
        ByteBuffer blob;
        ContentType contentType = ContentType.PLAIN_TEXT;
        if ( page instanceof Attachment ) {
            final Attachment attachment = ( Attachment ) page;
            InputStream is = null;
            try {
                final String filename = attachment.getFileName();
                contentType = getContentType( filename );
                is = engine.getManager( AttachmentManager.class ).getAttachmentStream( attachment );
                blob = ByteBuffer.wrap( IOUtils.toByteArray( is ) );
            } catch ( final ProviderException e ) {
                throw new IOException( e );
            } finally {
                IOUtils.closeQuietly( is, null );
            }
            // contentType should be set to its real value
        } else {
            final String text = engine.getManager( PageManager.class ).getPureText( page );
            blob = ByteBuffer.wrap( text.getBytes( StandardCharsets.UTF_8 ) );
        }
        return Document.builder().id( pageName ).title( title ).attributes( attrs ).blob( blob )
                .contentType( contentType ).build();
    }

    private DocumentAttribute newAttribute( final String key, final String value ) {
        return DocumentAttribute.builder().key( key ).value( DocumentAttributeValue.builder().stringValue( value ).build() ).build();
    }

    @SuppressWarnings( "unchecked" )
    private ContentType getContentType( final String filename ) {
        final String extention = FilenameUtils.getExtension( filename );
        final Map< String, String > ct = ( Map< String, String > ) this.contentTypes.get( "ContentTypes" );
        return ContentType.fromValue( ct.getOrDefault( extention, ContentType.PLAIN_TEXT.name() ) );
    }

    /**
     * Updater thread that updates Kendra indexes.
     */
    private static final class KendraUpdater extends WikiBackgroundThread {
        static final int INDEX_DELAY = 5;
        static final int INITIAL_DELAY = 10;
        private final KendraSearchProvider provider;

        private final int initialDelay;

        private WatchDog watchdog;

        private KendraUpdater( final Engine engine, final KendraSearchProvider provider, final int initialDelay, final int indexDelay ) {
            super( engine, indexDelay );
            this.provider = provider;
            this.initialDelay = initialDelay;
            setName( "JSPWiki Kendra Indexer" );
        }

        @Override
        public void startupTask() throws Exception {
            watchdog = WatchDog.getCurrentWatchDog( getEngine() );
            try {
                Thread.sleep( initialDelay * 1000L );
            } catch ( final InterruptedException e ) {
                throw new InternalWikiException( "Interrupted while waiting to start.", e );
            }
            watchdog.enterState( "Full reindex" );
            provider.initializeIndexAndDataSource();
            provider.doFullReindex();
            watchdog.exitState();
        }

        @Override
        public void backgroundTask() {
            watchdog.enterState( "Reindexing ...", 60 );
            provider.doPartialReindex();
            watchdog.exitState();
        }
    }

    private static class SearchResultImpl implements SearchResult {

        private final Page page;
        private final int score;
        private final String[] contexts;

        public SearchResultImpl( final Page page, final int score, final String[] contexts ) {
            this.page = page;
            this.score = score;
            this.contexts = contexts != null ? contexts.clone() : null;
        }

        @Override
        public Page getPage() {
            return this.page;
        }

        @Override
        public int getScore() {
            return this.score;
        }

        @Override
        public String[] getContexts() {
            return this.contexts;
        }
    }

    public KendraClient getKendra() {
        return kendra;
    }

    public void setKendra( final KendraClient kendra ) {
        this.kendra = kendra;
    }

    protected KendraClient buildClient() {
        return KendraClientBuilder.defaultClient();
    }

    public String getIndexName() {
        return indexName;
    }

    public String getDataSourceName() {
        return dataSourceName;
    }

}