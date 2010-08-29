/*
 * Licensed to Elastic Search and Shay Banon under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. Elastic Search licenses this
 * file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.gateway.local;

import org.elasticsearch.ElasticSearchException;
import org.elasticsearch.cluster.*;
import org.elasticsearch.cluster.block.ClusterBlock;
import org.elasticsearch.cluster.block.ClusterBlockLevel;
import org.elasticsearch.cluster.block.ClusterBlocks;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.cluster.metadata.MetaData;
import org.elasticsearch.cluster.metadata.MetaDataCreateIndexService;
import org.elasticsearch.cluster.routing.IndexRoutingTable;
import org.elasticsearch.cluster.routing.IndexShardRoutingTable;
import org.elasticsearch.cluster.routing.MutableShardRouting;
import org.elasticsearch.cluster.routing.RoutingNode;
import org.elasticsearch.common.collect.ImmutableSet;
import org.elasticsearch.common.collect.Sets;
import org.elasticsearch.common.component.AbstractLifecycleComponent;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.inject.Module;
import org.elasticsearch.common.io.FileSystemUtils;
import org.elasticsearch.common.io.Streams;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.ToXContent;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.common.xcontent.builder.BinaryXContentBuilder;
import org.elasticsearch.env.NodeEnvironment;
import org.elasticsearch.gateway.Gateway;
import org.elasticsearch.gateway.GatewayException;
import org.elasticsearch.index.gateway.local.LocalIndexGatewayModule;

import java.io.*;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.elasticsearch.cluster.ClusterState.*;
import static org.elasticsearch.cluster.metadata.MetaData.*;
import static org.elasticsearch.common.unit.TimeValue.*;

/**
 * @author kimchy (shay.banon)
 */
public class LocalGateway extends AbstractLifecycleComponent<Gateway> implements Gateway, ClusterStateListener {

    public static final ClusterBlock INDEX_NOT_RECOVERED_BLOCK = new ClusterBlock(3, "index not recovered (not enough nodes with shards allocated found)", ClusterBlockLevel.ALL);

    private File location;

    private final ClusterService clusterService;

    private final NodeEnvironment nodeEnv;

    private final MetaDataCreateIndexService createIndexService;

    private final TransportNodesListGatewayState listGatewayState;

    private volatile LocalGatewayState currentState;

    @Inject public LocalGateway(Settings settings, ClusterService clusterService, MetaDataCreateIndexService createIndexService,
                                NodeEnvironment nodeEnv, TransportNodesListGatewayState listGatewayState) {
        super(settings);
        this.clusterService = clusterService;
        this.createIndexService = createIndexService;
        this.nodeEnv = nodeEnv;
        this.listGatewayState = listGatewayState.initGateway(this);
    }

    @Override public String type() {
        return "local";
    }

    public LocalGatewayState currentState() {
        return this.currentState;
    }

    @Override protected void doStart() throws ElasticSearchException {
        // if this is not a possible master node or data node, bail, we won't save anything here...
        if (!clusterService.state().nodes().localNode().masterNode() || !clusterService.state().nodes().localNode().dataNode()) {
            location = null;
            return;
        }
        // create the location where the state will be stored
        this.location = new File(nodeEnv.nodeLocation(), "_state");
        this.location.mkdirs();

        try {
            long version = findLatestStateVersion();
            if (version != -1) {
                this.currentState = readState(Streams.copyToByteArray(new FileInputStream(new File(location, "state-" + version))));
            }
        } catch (Exception e) {
            logger.warn("failed to read local state", e);
        }

        clusterService.add(this);
    }

    @Override protected void doStop() throws ElasticSearchException {
        clusterService.remove(this);
    }

    @Override protected void doClose() throws ElasticSearchException {
    }

    @Override public void performStateRecovery(final GatewayStateRecoveredListener listener) throws GatewayException {
        Set<String> nodesIds = Sets.newHashSet();
        nodesIds.addAll(clusterService.state().nodes().dataNodes().keySet());
        nodesIds.addAll(clusterService.state().nodes().masterNodes().keySet());
        TransportNodesListGatewayState.NodesLocalGatewayState nodesState = listGatewayState.list(nodesIds, null).actionGet();

        TransportNodesListGatewayState.NodeLocalGatewayState electedState = null;
        for (TransportNodesListGatewayState.NodeLocalGatewayState nodeState : nodesState) {
            if (nodeState.state() == null) {
                continue;
            }
            if (electedState == null) {
                electedState = nodeState;
            } else if (nodeState.state().version() > electedState.state().version()) {
                electedState = nodeState;
            }
        }
        if (electedState == null) {
            logger.debug("no state elected");
            listener.onSuccess();
            return;
        }
        logger.debug("elected state from [{}]", electedState.node());
        final LocalGatewayState state = electedState.state();
        final AtomicInteger indicesCounter = new AtomicInteger(state.metaData().indices().size());
        clusterService.submitStateUpdateTask("local-gateway-elected-state", new ProcessedClusterStateUpdateTask() {
            @Override public ClusterState execute(ClusterState currentState) {
                MetaData.Builder metaDataBuilder = newMetaDataBuilder()
                        .metaData(currentState.metaData());
                // mark the metadata as read from gateway
                metaDataBuilder.markAsRecoveredFromGateway();

                return newClusterStateBuilder().state(currentState)
                        .version(state.version())
                        .metaData(metaDataBuilder).build();
            }

            @Override public void clusterStateProcessed(ClusterState clusterState) {
                // go over the meta data and create indices, we don't really need to copy over
                // the meta data per index, since we create the index and it will be added automatically
                for (final IndexMetaData indexMetaData : state.metaData()) {
                    try {
                        createIndexService.createIndex(new MetaDataCreateIndexService.Request("gateway", indexMetaData.index())
                                .settings(indexMetaData.settings())
                                .mappingsCompressed(indexMetaData.mappings())
                                .blocks(ImmutableSet.of(INDEX_NOT_RECOVERED_BLOCK))
                                .timeout(timeValueSeconds(30)),

                                new MetaDataCreateIndexService.Listener() {
                                    @Override public void onResponse(MetaDataCreateIndexService.Response response) {
                                        if (indicesCounter.decrementAndGet() == 0) {
                                            listener.onSuccess();
                                        }
                                    }

                                    @Override public void onFailure(Throwable t) {
                                        logger.error("failed to create index [{}]", indexMetaData.index(), t);
                                    }
                                });
                    } catch (IOException e) {
                        logger.error("failed to create index [{}]", indexMetaData.index(), e);
                    }
                }
            }
        });
    }

    @Override public Class<? extends Module> suggestIndexGateway() {
        return LocalIndexGatewayModule.class;
    }

    @Override public void reset() throws Exception {
        FileSystemUtils.deleteRecursively(nodeEnv.nodeLocation());
    }

    @Override public void clusterChanged(final ClusterChangedEvent event) {
        // nothing to do until we actually recover from hte gateway
        if (!event.state().metaData().recoveredFromGateway()) {
            return;
        }

        // go over the indices, if they are blocked, and all are allocated, update the cluster state that it is no longer blocked
        for (Map.Entry<String, ImmutableSet<ClusterBlock>> entry : event.state().blocks().indices().entrySet()) {
            final String index = entry.getKey();
            ImmutableSet<ClusterBlock> indexBlocks = entry.getValue();
            if (indexBlocks.contains(INDEX_NOT_RECOVERED_BLOCK)) {
                IndexRoutingTable indexRoutingTable = event.state().routingTable().index(index);
                if (indexRoutingTable != null && indexRoutingTable.allPrimaryShardsActive()) {
                    clusterService.submitStateUpdateTask("remove-index-block (all primary shards active for [" + index + "])", new ClusterStateUpdateTask() {
                        @Override public ClusterState execute(ClusterState currentState) {
                            ClusterBlocks.Builder blocks = ClusterBlocks.builder().blocks(currentState.blocks());
                            blocks.removeIndexBlock(index, INDEX_NOT_RECOVERED_BLOCK);
                            return ClusterState.builder().state(currentState).blocks(blocks).build();
                        }
                    });
                }
            }
        }

        if (!event.routingTableChanged() && !event.metaDataChanged()) {
            return;
        }

        // builder the current state
        LocalGatewayState.Builder builder = LocalGatewayState.builder();
        if (currentState != null) {
            builder.state(currentState);
        }
        builder.version(event.state().version());
        builder.metaData(event.state().metaData());
        // remove from the current state all the shards that are primary and started, we won't need them anymore
        for (IndexRoutingTable indexRoutingTable : event.state().routingTable()) {
            for (IndexShardRoutingTable indexShardRoutingTable : indexRoutingTable) {
                if (indexShardRoutingTable.primaryShard().active()) {
                    builder.remove(indexShardRoutingTable.shardId());
                }
            }
        }
        // now, add all the ones that are active and on this node
        RoutingNode routingNode = event.state().readOnlyRoutingNodes().node(event.state().nodes().localNodeId());
        if (routingNode != null) {
            // out node is not in play yet...
            for (MutableShardRouting shardRouting : routingNode) {
                if (shardRouting.active()) {
                    builder.put(shardRouting.shardId(), event.state().version());
                }
            }
        }

        try {
            LocalGatewayState stateToWrite = builder.build();
            BinaryXContentBuilder xContentBuilder = XContentFactory.contentBinaryBuilder(XContentType.JSON);
            xContentBuilder.prettyPrint();
            xContentBuilder.startObject();
            LocalGatewayState.Builder.toXContent(stateToWrite, xContentBuilder, ToXContent.EMPTY_PARAMS);
            xContentBuilder.endObject();

            File stateFile = new File(location, "state-" + event.state().version());
            FileOutputStream fos = new FileOutputStream(stateFile);
            fos.write(xContentBuilder.unsafeBytes(), 0, xContentBuilder.unsafeBytesLength());
            fos.close();

            FileSystemUtils.syncFile(stateFile);

            currentState = stateToWrite;
        } catch (IOException e) {
            logger.warn("failed to write updated state", e);
        }

        // delete all the other files
        File[] files = location.listFiles(new FilenameFilter() {
            @Override public boolean accept(File dir, String name) {
                return !name.equals("state-" + event.state().version());
            }
        });
        for (File file : files) {
            file.delete();
        }
    }

    private long findLatestStateVersion() throws IOException {
        long index = -1;
        for (File stateFile : location.listFiles()) {
            if (logger.isTraceEnabled()) {
                logger.trace("[findLatestState]: Processing [" + stateFile.getName() + "]");
            }
            String name = stateFile.getName();
            if (!name.startsWith("state-")) {
                continue;
            }
            long fileIndex = Long.parseLong(name.substring(name.indexOf('-') + 1));
            if (fileIndex >= index) {
                // try and read the meta data
                try {
                    readState(Streams.copyToByteArray(new FileInputStream(stateFile)));
                    index = fileIndex;
                } catch (IOException e) {
                    logger.warn("[findLatestState]: Failed to read state from [" + name + "], ignoring...", e);
                }
            }
        }

        return index;
    }

    private LocalGatewayState readState(byte[] data) throws IOException {
        XContentParser parser = null;
        try {
            parser = XContentFactory.xContent(XContentType.JSON).createParser(data);
            return LocalGatewayState.Builder.fromXContent(parser, settings);
        } finally {
            if (parser != null) {
                parser.close();
            }
        }
    }
}
