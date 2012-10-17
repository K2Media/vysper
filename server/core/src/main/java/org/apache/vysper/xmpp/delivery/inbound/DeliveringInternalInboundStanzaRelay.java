/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 *
 */
package org.apache.vysper.xmpp.delivery.inbound;

import java.io.IOException;
import java.io.Writer;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.apache.vysper.compliance.SpecCompliant;
import org.apache.vysper.storage.StorageProviderRegistry;
import org.apache.vysper.storage.logstanzas.LogStorageProvider;
import org.apache.vysper.xmpp.addressing.Entity;
import org.apache.vysper.xmpp.addressing.EntityUtils;
import org.apache.vysper.xmpp.authentication.AccountManagement;
import org.apache.vysper.xmpp.delivery.OfflineStanzaReceiver;
import org.apache.vysper.xmpp.delivery.StanzaRelay;
import org.apache.vysper.xmpp.delivery.failure.DeliveredToOfflineReceiverException;
import org.apache.vysper.xmpp.delivery.failure.DeliveryException;
import org.apache.vysper.xmpp.delivery.failure.DeliveryFailureStrategy;
import org.apache.vysper.xmpp.delivery.failure.LocalRecipientOfflineException;
import org.apache.vysper.xmpp.delivery.failure.NoSuchLocalUserException;
import org.apache.vysper.xmpp.delivery.failure.ServiceNotAvailableException;
import org.apache.vysper.xmpp.modules.extension.xep0160_offline_storage.*;
import org.apache.vysper.xmpp.protocol.SessionStateHolder;
import org.apache.vysper.xmpp.protocol.StanzaHandler;
import org.apache.vysper.xmpp.protocol.StanzaProcessor;
import org.apache.vysper.xmpp.protocol.worker.InboundStanzaProtocolWorker;
import org.apache.vysper.xmpp.server.ServerRuntimeContext;
import org.apache.vysper.xmpp.server.SessionContext;
import org.apache.vysper.xmpp.server.SessionState;
import org.apache.vysper.xmpp.server.resources.ManagedThreadPool;
import org.apache.vysper.xmpp.server.resources.ManagedThreadPoolUtil;
import org.apache.vysper.xmpp.stanza.IQStanza;
import org.apache.vysper.xmpp.stanza.MessageStanza;
import org.apache.vysper.xmpp.stanza.MessageStanzaType;
import org.apache.vysper.xmpp.stanza.PresenceStanza;
import org.apache.vysper.xmpp.stanza.Stanza;
import org.apache.vysper.xmpp.stanza.XMPPCoreStanza;
import org.apache.vysper.xmpp.state.resourcebinding.ResourceRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * relays all 'incoming' stanzas to internal sessions, acts as a 'stage' by using a ThreadPoolExecutor
 * 'incoming' here means:
 * a. stanzas coming in from other servers
 * b. stanzas coming from other (local) sessions and are targeted to clients on this server
 *  
 * @author The Apache MINA Project (dev@mina.apache.org)
 */
public class DeliveringInternalInboundStanzaRelay implements StanzaRelay, ManagedThreadPool {

    final Logger logger = LoggerFactory.getLogger(DeliveringInternalInboundStanzaRelay.class);

    private static class RejectedDeliveryHandler implements RejectedExecutionHandler {
        
        DeliveringInternalInboundStanzaRelay relay;
        Logger logger;

        private RejectedDeliveryHandler(DeliveringInternalInboundStanzaRelay relay, Logger logger) {
            this.relay = relay;
            this.logger = logger;
        }

        public void rejectedExecution(Runnable runnable, ThreadPoolExecutor threadPoolExecutor) {
            logger.info("relaying of internal inbound stanza has been rejected");
        }
    }
    
    private static final InboundStanzaProtocolWorker INBOUND_STANZA_PROTOCOL_WORKER = new InboundStanzaProtocolWorker();

    private static final Integer PRIO_THRESHOLD = 0;

    protected ResourceRegistry resourceRegistry;

    protected ExecutorService executor;

    protected AccountManagement accountVerification;

    protected OfflineStanzaReceiver offlineStanzaReceiver = null;

    protected Entity serverEntity;

    protected ServerRuntimeContext serverRuntimeContext = null;
    
    protected LogStorageProvider logStorageProvider = null;
    
    protected long lastCompleted = 0;
    protected long lastDumpTimestamp = 0;

    public DeliveringInternalInboundStanzaRelay(Entity serverEntity, ResourceRegistry resourceRegistry,
            StorageProviderRegistry storageProviderRegistry) {
        this(serverEntity, resourceRegistry, (AccountManagement) storageProviderRegistry
                .retrieve(AccountManagement.class),(OfflineStanzaReceiver)storageProviderRegistry.retrieve(OfflineStorageProvider.class));
    }

    public DeliveringInternalInboundStanzaRelay(Entity serverEntity, ResourceRegistry resourceRegistry,
            AccountManagement accountVerification, OfflineStanzaReceiver offlineStanzaReceiver) {
        this.serverEntity = serverEntity;
        this.resourceRegistry = resourceRegistry;
        this.accountVerification = accountVerification;
        this.offlineStanzaReceiver =offlineStanzaReceiver;
        int coreThreadCount = 18;
        int maxThreadCount = 36;
        int threadTimeoutSeconds = 2 * 60;
        this.executor = new ThreadPoolExecutor(coreThreadCount, maxThreadCount, threadTimeoutSeconds, TimeUnit.SECONDS,
                new LinkedBlockingQueue<Runnable>(), new RejectedDeliveryHandler(this, logger));
    }

    /*package*/ DeliveringInternalInboundStanzaRelay(ExecutorService executor) {
        this.executor = executor;
    }

    public void setServerRuntimeContext(ServerRuntimeContext serverRuntimeContext) {
        this.serverRuntimeContext = serverRuntimeContext;
    }

    public final void setLogStorageProvider(final LogStorageProvider logStorageProvider) {
        this.logStorageProvider = logStorageProvider;
    }

    public void setMaxThreadCount(int maxThreadPoolCount) {
        if (!(executor instanceof ThreadPoolExecutor)) {
            throw new IllegalStateException("cannot set max thread count for " + executor.getClass());
        }
        ThreadPoolExecutor threadPoolExecutor = (ThreadPoolExecutor)executor;
        threadPoolExecutor.setCorePoolSize(maxThreadPoolCount);
        threadPoolExecutor.setMaximumPoolSize(2*maxThreadPoolCount);
    }
    
    public void setThreadTimeoutSeconds(int threadTimeoutSeconds) {
        if (!(executor instanceof ThreadPoolExecutor)) {
            throw new IllegalStateException("cannot set thread timeout for " + executor.getClass());
        }
        ThreadPoolExecutor threadPoolExecutor = (ThreadPoolExecutor)executor;
        threadPoolExecutor.setKeepAliveTime(threadTimeoutSeconds, TimeUnit.SECONDS);
    }
    
    public void dumpThreadPoolInfo(Writer writer) throws IOException {
        if (!(executor instanceof ThreadPoolExecutor)) {
            throw new IllegalStateException("cannot dump info for " + executor.getClass());
        }
        ThreadPoolExecutor pool = (ThreadPoolExecutor)executor;

        final long now = System.currentTimeMillis();
        writer.append("==== internalRelay:").append("\n");
        ManagedThreadPoolUtil.writeThreadPoolInfo(writer, pool);
        final long completedTaskCount = pool.getCompletedTaskCount();
        if (lastDumpTimestamp > 0) {
            writer.append("throughput=\t").append(Long.toString(completedTaskCount - lastCompleted))
                                          .append(" per ").append(Long.toString(now - lastDumpTimestamp)).append("\n");
        }
        lastDumpTimestamp = now;
        lastCompleted = completedTaskCount;
    }
    
    public void relay(Entity receiver, Stanza stanza, DeliveryFailureStrategy deliveryFailureStrategy)
            throws DeliveryException {
        if (!isRelaying()) {
            throw new ServiceNotAvailableException("internal inbound relay is not relaying");
        }
        
        Future<RelayResult> resultFuture = executor.submit(new Relay(receiver, stanza, deliveryFailureStrategy));
        if (this.logStorageProvider != null) {
            this.logStorageProvider.logStanza(receiver, stanza);
        }
    }

    public boolean isRelaying() {
        return !executor.isShutdown();
    }

    public void stop() {
        this.executor.shutdown();
    }

    private class Relay implements Callable<RelayResult> {
        private Entity receiver;

        private Stanza stanza;

        private DeliveryFailureStrategy deliveryFailureStrategy;

        protected final UnmodifyableSessionStateHolder sessionStateHolder = new UnmodifyableSessionStateHolder();

        Relay(Entity receiver, Stanza stanza, DeliveryFailureStrategy deliveryFailureStrategy) {
            this.receiver = receiver;
            this.stanza = stanza;
            this.deliveryFailureStrategy = deliveryFailureStrategy;
        }

        public Entity getReceiver() {
            return receiver;
        }

        public Stanza getStanza() {
            return stanza;
        }

        public DeliveryFailureStrategy getDeliveryFailureStrategy() {
            return deliveryFailureStrategy;
        }

        public RelayResult call() {
            RelayResult relayResult = deliver();
            if (relayResult == null || !relayResult.hasProcessingErrors()) {
                return relayResult;
            } else {
                if (containsDeliveredToOfflineProviderException(relayResult.getProcessingErrors())) {

                    logger.debug("Not going to persist to OnlineStorageProvider because relayResult.processingErrors contains a DeliveredToOfflineStorageProvider exception");

                } else if (offlineStanzaReceiver instanceof OnlineStorageProvider) {
                    logger.debug("About to persist stanza to OnlineStorageProvider");
                    ((OnlineStorageProvider) offlineStanzaReceiver).storeStanza(stanza, true);
                } else {
                    logger.debug("Not persisting to OnlineStorageProvider because offlineStorageProvider is not castable to OnlineStorageProvider");
                }
            }

            return runFailureStrategy(relayResult);
        }

        protected boolean containsDeliveredToOfflineProviderException(List<DeliveryException> deliveryExceptions) {
            for (DeliveryException deliveryException : deliveryExceptions) {
                if (deliveryException instanceof DeliveredToOfflineReceiverException) {
                    return true;
                }
            }
            return false;
        }

        private RelayResult runFailureStrategy(RelayResult relayResult) {
            if (deliveryFailureStrategy != null) {
                try {
                    deliveryFailureStrategy.process(stanza, relayResult.getProcessingErrors());
                } catch (DeliveryException e) {
                    return new RelayResult(e);
                } catch (RuntimeException e) {
                    return new RelayResult(new DeliveryException(e));
                }
            }
            // TODO throw relayResult.getProcessingError() in some appropriate context
            return relayResult;
        }

        /**
         * @return
         */
        @SpecCompliant(spec = "draft-ietf-xmpp-3921bis-00", section = "8.", status = SpecCompliant.ComplianceStatus.IN_PROGRESS, coverage = SpecCompliant.ComplianceCoverage.COMPLETE)
        protected RelayResult deliver() {
            try {
                String receiverDomain = receiver.getDomain();
                if (receiverDomain != null && !EntityUtils.isAddressingServer(receiver, serverEntity)) {
                    logger.debug("attempting to deliver stanza via inbound relay but receiverDomain seems to be addressing a component: " + receiver + " serverEntity: " + serverEntity);
                    if (serverRuntimeContext == null) {
                        return new RelayResult(new ServiceNotAvailableException(
                                "cannot retrieve component from server context"));
                    }
                    if (!EntityUtils.isAddressingServerComponent(receiver, serverEntity)) {
                        return new RelayResult(new ServiceNotAvailableException("unsupported domain " + receiverDomain));
                    }

                    StanzaProcessor processor = serverRuntimeContext.getComponentStanzaProcessor(receiver);
                    if (processor == null) {
                        return new RelayResult(new ServiceNotAvailableException(
                                "cannot retrieve component stanza processor for" + receiverDomain));
                    }

                    processor.processStanza(serverRuntimeContext, null, stanza, null);
                    return new RelayResult().setProcessed();
                }

                if (receiver.isResourceSet()) {
                    return deliverToFullJID();
                } else {
                    return deliverToBareJID();
                }

            } catch (RuntimeException e) {
                return new RelayResult(new DeliveryException(e));
            }
        }

        @SpecCompliant(spec = "draft-ietf-xmpp-3921bis-00", section = "8.3.", status = SpecCompliant.ComplianceStatus.IN_PROGRESS, coverage = SpecCompliant.ComplianceCoverage.COMPLETE)
        private RelayResult deliverToBareJID() {
            XMPPCoreStanza xmppStanza = XMPPCoreStanza.getWrapper(stanza);
            if (xmppStanza == null)
                return new RelayResult(new DeliveryException(
                        "unable to deliver stanza which is not IQ, presence or message"));

            logger.debug("Attempting to deliver via internal relay to bareJID: " + xmppStanza.toString());

            if (PresenceStanza.isOfType(stanza)) {
                return relayToAllSessions();
            } else if (MessageStanza.isOfType(stanza)) {
                MessageStanza messageStanza = (MessageStanza) xmppStanza;
                MessageStanzaType messageStanzaType = messageStanza.getMessageType();
                switch (messageStanzaType) {
                case CHAT:
                case NORMAL:
                    return serverRuntimeContext.getServerFeatures().isDeliveringMessageToHighestPriorityResourcesOnly() ? relayToBestSessions(false)
                            : relayToAllSessions(0);

                case ERROR:
                    // silently ignore
                    return null;

                case GROUPCHAT:
                    return new RelayResult(new ServiceNotAvailableException());

                case HEADLINE:
                    return relayToAllSessions();

                default:
                    throw new RuntimeException("unhandled message type " + messageStanzaType.value());
                }
            } else if (IQStanza.isOfType(stanza)) {
                // TODO handle on behalf of the user/client
                return relayToBestSessions(true);
            }

            return relayNotPossible();
        }

        @SpecCompliant(spec = "draft-ietf-xmpp-3921bis-00", section = "8.2.", status = SpecCompliant.ComplianceStatus.IN_PROGRESS, coverage = SpecCompliant.ComplianceCoverage.COMPLETE)
        private RelayResult deliverToFullJID() {
            XMPPCoreStanza xmppStanza = XMPPCoreStanza.getWrapper(stanza);
            if (xmppStanza == null)
                new RelayResult(new DeliveryException("unable to deliver stanza which is not IQ, presence or message"));

            logger.debug("Attempting to deliver via internal relay to fullJID: " + xmppStanza.toString());

            // all special cases are handled by the inbound handlers!
            if (PresenceStanza.isOfType(stanza)) {
                // TODO cannot deliver presence with type  AVAIL or UNAVAIL: silently ignore
                // TODO cannot deliver presence with type  SUBSCRIBE: see 3921bis section 3.1.3
                // TODO cannot deliver presence with type  (UN)SUBSCRIBED, UNSUBSCRIBE: silently ignore
                return relayToBestSessions(false);
            } else if (MessageStanza.isOfType(stanza)) {
                MessageStanza messageStanza = (MessageStanza) xmppStanza;
                MessageStanzaType messageStanzaType = messageStanza.getMessageType();
                boolean fallbackToBareJIDAllowed = messageStanzaType == MessageStanzaType.CHAT
                        || messageStanzaType == MessageStanzaType.HEADLINE
                        || messageStanzaType == MessageStanzaType.NORMAL;
                // TODO cannot deliver ERROR: silently ignore
                // TODO cannot deliver GROUPCHAT: service n/a
                return relayToBestSessions(fallbackToBareJIDAllowed);

            } else if (IQStanza.isOfType(stanza)) {
                // TODO no resource matches: service n/a
                return relayToBestSessions(true);
            }

            // for any other type of stanza 
            return new RelayResult(new ServiceNotAvailableException());
        }

        private RelayResult relayNotPossible() {
            if (!accountVerification.verifyAccountExists(receiver)) {
                logger.warn("cannot relay to unexisting receiver {} stanza {}", receiver.getFullQualifiedName(), stanza
                        .toString());
                return new RelayResult(new NoSuchLocalUserException());
            } else if (offlineStanzaReceiver != null) {
                logger.debug("About to send stanza to offlineReceiver to be persisted (since user appears to be offline)");
                offlineStanzaReceiver.receive(stanza);
                return new RelayResult(new DeliveredToOfflineReceiverException());
            } else {
                logger.warn("cannot relay to offline receiver {} stanza {}", receiver.getFullQualifiedName(), stanza
                        .toString());
                return new RelayResult(new LocalRecipientOfflineException());
            }
        }

        protected RelayResult relayToBestSessions(final boolean fallbackToBareJIDAllowed) {
            List<SessionContext> receivingSessions = resourceRegistry.getHighestPrioSessions(receiver, PRIO_THRESHOLD);

            if (receivingSessions.size() == 0 && receiver.isResourceSet() && fallbackToBareJIDAllowed) {
                logger.debug("found no sessions for relayToBestSessions");
                // no concrete session for this resource has been found
                // fall back to bare JID
                receivingSessions = resourceRegistry.getHighestPrioSessions(receiver.getBareJID(), PRIO_THRESHOLD);
            }

            if (receivingSessions.size() == 0) {
                return relayNotPossible();
            }

            RelayResult relayResult = new RelayResult();
            logger.debug("found " + receivingSessions.size() + " sessions for relayToBestSessions: " + (stanza != null ? stanza.toString() : "null stanza"));
            for (SessionContext receivingSession : receivingSessions) {
                if (receivingSession.getState() != SessionState.AUTHENTICATED) {
                    logger.error("Can't relay to session: " + receivingSession + " because state is not authenticated");
                    relayResult.addProcessingError(new DeliveryException("no relay to non-authenticated sessions"));
                    continue;
                }
                try {
                    StanzaHandler stanzaHandler = receivingSession.getServerRuntimeContext().getHandler(stanza);
                    logger.debug("About to relay to stanzaHandler: " + (stanzaHandler != null ? stanzaHandler.toString() : " null"));
                    INBOUND_STANZA_PROTOCOL_WORKER.processStanza(receivingSession, sessionStateHolder, stanza,
                            stanzaHandler);
                } catch (Exception e) {
                    if (stanza != null)
                        logger.error("Error relaying stanza via protocol worker in inbound relay: " + stanza.toString());
                    relayResult.addProcessingError(new DeliveryException("no relay to non-authenticated sessions"));
                    continue;
                }

            }
            return relayResult.setProcessed();
        }

        protected RelayResult relayToAllSessions() {
            return relayToAllSessions(null);
        }

        protected RelayResult relayToAllSessions(Integer prioThreshold) {

            List<SessionContext> receivingSessions = prioThreshold == null ? resourceRegistry.getSessions(receiver)
                    : resourceRegistry.getSessions(receiver, prioThreshold);

            if (receivingSessions.size() == 0) {
                return relayNotPossible();
            }

            if (receivingSessions.size() > 1) {
                logger.warn("multiplexing: {} sessions will be processing {} ", receivingSessions.size(), stanza);
            }

            RelayResult relayResult = new RelayResult();

            logger.debug("Relaying to allSessions with a total number of: " + receivingSessions.size() + " sessions");
            for (SessionContext sessionContext : receivingSessions) {
                logger.debug("Relating to sessionContext: " + sessionContext.toString());
                if (sessionContext.getState() != SessionState.AUTHENTICATED) {
                    logger.error("Can't relay to one of the sessions because the sesion is not authenticated");
                    relayResult.addProcessingError(new DeliveryException("no relay to non-authenticated sessions"));
                    continue;
                }
                try {
                    StanzaHandler stanzaHandler = sessionContext.getServerRuntimeContext().getHandler(stanza);
                    INBOUND_STANZA_PROTOCOL_WORKER.processStanza(sessionContext, sessionStateHolder, stanza,
                            stanzaHandler);
                } catch (Exception e) {
                    if (stanza != null)
                        logger.error("Error relaying stanza via protocol worker in inbound relay: " + stanza.toString());
                    relayResult.addProcessingError(new DeliveryException(e));
                }
            }

            return relayResult.setProcessed(); // return success result
        }
    }

    private static class UnmodifyableSessionStateHolder extends SessionStateHolder {

        @Override
        public void setState(SessionState newState) {
            throw new RuntimeException("unable to alter state");
        }

        @Override
        public SessionState getState() {
            return SessionState.AUTHENTICATED;
        }
    }

}
