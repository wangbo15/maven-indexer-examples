package org.apache.maven.indexer.example;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.Query;
import org.apache.maven.index.ArtifactInfo;
import org.apache.maven.index.ArtifactInfoGroup;
import org.apache.maven.index.FlatSearchRequest;
import org.apache.maven.index.FlatSearchResponse;
import org.apache.maven.index.GroupedSearchRequest;
import org.apache.maven.index.GroupedSearchResponse;
import org.apache.maven.index.Grouping;
import org.apache.maven.index.MAVEN;
import org.apache.maven.index.NexusIndexer;
import org.apache.maven.index.SearchType;
import org.apache.maven.index.context.IndexCreator;
import org.apache.maven.index.context.IndexingContext;
import org.apache.maven.index.search.grouping.GAGrouping;
import org.apache.maven.index.updater.IndexUpdateRequest;
import org.apache.maven.index.updater.IndexUpdateResult;
import org.apache.maven.index.updater.IndexUpdater;
import org.apache.maven.index.updater.ResourceFetcher;
import org.apache.maven.index.updater.WagonHelper;
import org.apache.maven.wagon.Wagon;
import org.apache.maven.wagon.events.TransferEvent;
import org.apache.maven.wagon.events.TransferListener;
import org.apache.maven.wagon.observers.AbstractTransferListener;
import org.codehaus.plexus.DefaultPlexusContainer;
import org.codehaus.plexus.util.StringUtils;

/**
 * Hello world!
 */
public class App
{

    public static void main( String[] args )
        throws Exception
    {
        // create Plexus IoC (actually SISU-plexus compat)
        DefaultPlexusContainer plexus = new DefaultPlexusContainer();

        // Files where local cache is (if any) and Lucene Index should be located
        File centralLocalCache = new File( "target/central-cache" );
        File centralIndexDir = new File( "target/central-index" );

        // Creators we want to use (search for fields it defines)
        List<IndexCreator> indexers = new ArrayList<IndexCreator>();
        indexers.add( plexus.lookup( IndexCreator.class, "min" ) );
        indexers.add( plexus.lookup( IndexCreator.class, "jarContent" ) );
        indexers.add( plexus.lookup( IndexCreator.class, "maven-plugin" ) );

        // lookup the indexer instance from plexus
        NexusIndexer nexusIndexer = plexus.lookup( NexusIndexer.class );

        // Create context for central repository index
        IndexingContext centralContext =
            nexusIndexer.addIndexingContextForced( "central-context", "central", centralLocalCache, centralIndexDir,
                "http://repo1.maven.org/maven2", null, indexers );

        // Update the index (incremental update will happen if this is not 1st run and files are not deleted)
        // This whole block below should not be executed on every app start, but rather controlled by some configuration
        // since this block will always emit at least one HTTP GET. Central indexes are updated once a week, but
        // other index sources might have different index publishing frequency.
        // Preferred frequency is once a week.
        if ( true )
        {
            System.out.println( "Updating Index..." );
            System.out.println( "This might take a while on first run, so please be patient!" );
            // Create ResourceFetcher implementation to be used with IndexUpdateRequest
            // Here, we use Wagon based one as shorthand, but all we need is a ResourceFetcher implementation
            Wagon wagon = plexus.lookup( Wagon.class, "http" );
            TransferListener listener = new AbstractTransferListener()
            {
                public void transferStarted( TransferEvent transferEvent )
                {
                    System.out.print( "  Downloading " + transferEvent.getResource().getName() );
                }

                public void transferProgress( TransferEvent transferEvent, byte[] buffer, int length )
                {
                }

                public void transferCompleted( TransferEvent transferEvent )
                {
                    System.out.println( " - Done" );
                }
            };
            ResourceFetcher resourceFetcher = new WagonHelper.WagonFetcher( wagon, listener, null, null );

            Date centralContextCurrentTimestamp = centralContext.getTimestamp();
            IndexUpdateRequest updateRequest = new IndexUpdateRequest( centralContext, resourceFetcher );
            IndexUpdater updater = plexus.lookup( IndexUpdater.class );
            IndexUpdateResult updateResult = updater.fetchAndUpdateIndex( updateRequest );
            if ( updateResult.isFullUpdate() )
            {
                System.out.println( "Full update happened!" );
            }
            else if ( updateResult.getTimestamp().equals( centralContextCurrentTimestamp ) )
            {
                System.out.println( "No update needed, index is up to date!" );
            }
            else
            {
                System.out.println( "Incremental update happened, change covered " + centralContextCurrentTimestamp
                    + " - " + updateResult.getTimestamp() + " period." );
            }

            System.out.println();
        }

        System.out.println();
        System.out.println( "Using index" );
        System.out.println( "===========" );
        System.out.println();

        // Use index
        BooleanQuery bq;

        // Searching for some artifact
        Query gidQ = nexusIndexer.constructQuery( MAVEN.GROUP_ID, "org.apache.maven.indexer", SearchType.EXACT );
        Query aidQ = nexusIndexer.constructQuery( MAVEN.ARTIFACT_ID, "indexer-artifact", SearchType.EXACT );

        bq = new BooleanQuery();
        bq.add( gidQ, Occur.MUST );
        bq.add( aidQ, Occur.MUST );

        searchAndDump( nexusIndexer, "all artifacts under GA org.apache.maven.indexer:indexer-artifact", bq );

        // Searching for some main artifact
        bq = new BooleanQuery();
        bq.add( gidQ, Occur.MUST );
        bq.add( aidQ, Occur.MUST );
        bq.add( nexusIndexer.constructQuery( MAVEN.CLASSIFIER, "*", SearchType.EXACT ), Occur.MUST_NOT );

        searchAndDump( nexusIndexer, "main artifacts under GA org.apache.maven.indexer:indexer-artifact", bq );

        // doing sha1 search
        searchAndDump( nexusIndexer, "SHA1 7ab67e6b20e5332a7fb4fdf2f019aec4275846c2",
            nexusIndexer.constructQuery( MAVEN.SHA1, "7ab67e6b20e5332a7fb4fdf2f019aec4275846c2", SearchType.EXACT ) );

        searchAndDump( nexusIndexer, "SHA1 7ab67e6b20 (partial hash)",
            nexusIndexer.constructQuery( MAVEN.SHA1, "7ab67e6b20", SearchType.SCORED ) );

        // doing classname search (incomplete classname)
        searchAndDump( nexusIndexer, "classname DefaultNexusIndexer",
            nexusIndexer.constructQuery( MAVEN.CLASSNAMES, "DefaultNexusIndexer", SearchType.SCORED ) );

        // doing search for all "canonical" maven plugins latest versions
        bq = new BooleanQuery();
        bq.add( nexusIndexer.constructQuery( MAVEN.PACKAGING, "maven-plugin", SearchType.EXACT ), Occur.MUST );
        bq.add( nexusIndexer.constructQuery( MAVEN.GROUP_ID, "org.apache.maven.plugins", SearchType.EXACT ), Occur.MUST );
        searchGroupedAndDump( nexusIndexer, "all \"canonical\" maven plugins", bq, new GAGrouping() );

        // close cleanly
        nexusIndexer.removeIndexingContext( centralContext, false );
    }

    public static void searchAndDump( NexusIndexer nexusIndexer, String descr, Query q )
        throws IOException
    {
        System.out.println( "Searching for " + descr );

        FlatSearchResponse response = nexusIndexer.searchFlat( new FlatSearchRequest( q ) );

        for ( ArtifactInfo ai : response.getResults() )
        {
            System.out.println( ai.toString() );
        }

        System.out.println( "------" );
        System.out.println( "Total: " + response.getTotalHits() );
        System.out.println();
    }

    public static void searchGroupedAndDump( NexusIndexer nexusIndexer, String descr, Query q, Grouping g )
        throws IOException
    {
        System.out.println( "Searching for " + descr );

        GroupedSearchResponse response = nexusIndexer.searchGrouped( new GroupedSearchRequest( q, g ) );

        for ( Map.Entry<String, ArtifactInfoGroup> entry : response.getResults().entrySet() )
        {
            ArtifactInfo ai = entry.getValue().getArtifactInfos().iterator().next();
            System.out.println( "* Plugin " + ai.artifactId );
            System.out.println( "  Latest version:  " + ai.version );
            System.out.println( StringUtils.isBlank( ai.description ) ? "No description in plugin's POM."
                : StringUtils.abbreviate( ai.description, 60 ) );
            System.out.println();
        }

        System.out.println( "------" );
        System.out.println( "Total record hits: " + response.getTotalHits() );
        System.out.println();
    }
}