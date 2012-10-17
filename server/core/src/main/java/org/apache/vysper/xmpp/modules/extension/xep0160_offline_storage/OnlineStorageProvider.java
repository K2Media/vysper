package org.apache.vysper.xmpp.modules.extension.xep0160_offline_storage;

import java.util.Collection;

import org.apache.vysper.storage.StorageProvider;
import org.apache.vysper.xmpp.addressing.Entity;
import org.apache.vysper.xmpp.delivery.OfflineStanzaReceiver;
import org.apache.vysper.xmpp.stanza.Stanza;

public interface OnlineStorageProvider {

	void storeStanza(Stanza stanza, Boolean alreadyViewed);

    Stanza getStanzaByMessageId(String bareJID, String messageId);
}