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

package org.apache.vysper.xmpp.modules.core.base.handler;

import org.apache.vysper.xml.fragment.Attribute;
import org.apache.vysper.xml.fragment.XMLElement;
import org.apache.vysper.xml.fragment.XMLSemanticError;
import org.apache.vysper.xmpp.addressing.Entity;
import org.apache.vysper.xmpp.addressing.EntityImpl;
import org.apache.vysper.xmpp.delivery.StanzaRelay;
import org.apache.vysper.xmpp.delivery.failure.ReturnErrorToSenderFailureStrategy;
import org.apache.vysper.xmpp.modules.extension.xep0160_offline_storage.OfflineStorageProvider;
import org.apache.vysper.xmpp.server.ServerRuntimeContext;
import org.apache.vysper.xmpp.server.SessionContext;
import org.apache.vysper.xmpp.stanza.MessageStanza;
import org.apache.vysper.xmpp.stanza.Stanza;
import org.apache.vysper.xmpp.stanza.StanzaBuilder;
import org.apache.vysper.xmpp.stanza.XMPPCoreStanza;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * handling message stanzas
 *
 * @author The Apache MINA Project (dev@mina.apache.org)
 */
public class MessageHandler extends XMPPCoreStanzaHandler {

    final Logger logger = LoggerFactory.getLogger(MessageHandler.class);

    private ServerRuntimeContext serverRuntimeContext;

    public MessageHandler(ServerRuntimeContext serverRuntimeContext) {
        this.serverRuntimeContext = serverRuntimeContext;
    }

    public MessageHandler() {

    }

    public String getName() {
        return "message";
    }

    public boolean verify(Stanza stanza) {
        if (stanza == null) return false;

        boolean typeVerified = verifyType(stanza);
        // we removed namespace because this should work for both s2s and client namespaces to support clustering
        // boolean namespaceVerified = verifyNamespace(stanza);
        return typeVerified;
    }

    @Override
    protected boolean verifyType(Stanza stanza) {
        return MessageStanza.isOfType(stanza);
    }

    @Override
    protected Stanza executeCore(XMPPCoreStanza stanza, ServerRuntimeContext serverRuntimeContext,
            boolean isOutboundStanza, SessionContext sessionContext) {

        // (try to) read thread id
        String threadId = null;
        XMLElement threadElement = null;
        try {
            threadElement = stanza.getSingleInnerElementsNamed("thread");
            if (threadElement != null && threadElement.getSingleInnerText() != null) {
                try {
                    threadId = threadElement.getSingleInnerText().getText();
                } catch (Exception _) {
                    threadId = null;
                }
            }
        } catch (XMLSemanticError _) {
            threadId = null;
        }

        // (try to) read subject id
        String subject = null;
        XMLElement subjectElement = null;
        try {
            subjectElement = stanza.getSingleInnerElementsNamed("subject");
            if (subjectElement != null && subjectElement.getSingleInnerText() != null) {
                try {
                    subject = subjectElement.getSingleInnerText().getText();
                } catch (Exception _) {
                    subject = null;
                }
            }
        } catch (XMLSemanticError _) {
            subject = null;
        }

        // TODO inspect all BODY elements and make sure they conform to the spec

        if (isOutboundStanza) {
            // check if message reception is turned of either globally or locally
            if (!serverRuntimeContext.getServerFeatures().isRelayingMessages()
                    || (sessionContext != null && sessionContext
                            .getAttribute(SessionContext.SESSION_ATTRIBUTE_MESSAGE_STANZA_NO_RECEIVE) != null)) {
                return null;
            }

            Entity from = stanza.getFrom();
            if (from == null || !from.isResourceSet()) {
                // rewrite stanza with new from
                String resource = serverRuntimeContext.getResourceRegistry()
                        .getUniqueResourceForSession(sessionContext);
                if (resource == null)
                    throw new IllegalStateException("could not determine unique resource");
                from = new EntityImpl(sessionContext.getInitiatingEntity(), resource);
                logger.debug("No from set on stanza: " + stanza.toString() + " using from: " + from.toString());
                StanzaBuilder stanzaBuilder = new StanzaBuilder(stanza.getName(), stanza.getNamespaceURI());
                for (Attribute attribute : stanza.getAttributes()) {
                    if ("from".equals(attribute.getName()))
                        continue;
                    stanzaBuilder.addAttribute(attribute);
                }
                stanzaBuilder.addAttribute("from", from.getFullQualifiedName());
                for (XMLElement preparedElement : stanza.getInnerElements()) {
                    stanzaBuilder.addPreparedElement(preparedElement);
                }
                stanza = XMPPCoreStanza.getWrapper(stanzaBuilder.build());
                // check for message receipt xep-184
                List<XMLElement> receipts = stanza.getInnerElementsNamed("received", "urn:xmpp:receipts");
                if (receipts != null && !receipts.isEmpty()) {
                    // see if we have a receipt for a message
                    if (serverRuntimeContext != null) {
                        OfflineStorageProvider offlineStorageProvider = serverRuntimeContext.getStorageProvider(OfflineStorageProvider.class);
                    }
            }

            StanzaRelay stanzaRelay = serverRuntimeContext.getStanzaRelay();
            try {
                stanzaRelay.relay(stanza.getTo(), stanza, new ReturnErrorToSenderFailureStrategy(stanzaRelay));
            } catch (Exception e) {
                logger.error("Error relaying stanza in MessageHandler: " + stanza.toString(), e);
                // TODO return error stanza
                e.printStackTrace(); //To change body of catch statement use File | Settings | File Templates.
            }
        } else if (sessionContext != null) {
            sessionContext.getResponseWriter().write(stanza);
        } else {
            throw new IllegalStateException("handling offline messages not implemented");
        }
        return null;
    }
}
