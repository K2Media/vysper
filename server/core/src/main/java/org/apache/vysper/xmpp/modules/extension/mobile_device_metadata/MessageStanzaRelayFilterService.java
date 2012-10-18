package org.apache.vysper.xmpp.modules.extension.mobile_device_metadata;

import org.apache.vysper.xmpp.modules.ServerRuntimeContextService;
import org.apache.vysper.xmpp.server.SessionContext;
import org.apache.vysper.xmpp.stanza.Stanza;

public interface MessageStanzaRelayFilterService extends ServerRuntimeContextService {

    public static final String SERVICE_NAME = "messageStanzaRelayFilterService";

    /**
     * Whether to allow a message to be relayed to outbound channel
     *
     * @param stanza
     * @return whether to allow message to be sent (true means it will be relayed)
     */
    public boolean proceedOutboundRelay(Stanza stanza, SessionContext sessionContext);
}
