/*
* Copyright (C) 2010 Mamadou Diop.
*
* Contact: Mamadou Diop <diopmamadou(at)doubango.org>
*	
* This file is part of imsdroid Project (http://code.google.com/p/imsdroid)
*
* imsdroid is free software: you can redistribute it and/or modify it under the terms of 
* the GNU General Public License as published by the Free Software Foundation, either version 3 
* of the License, or (at your option) any later version.
*	
* imsdroid is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
* without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  
* See the GNU General Public License for more details.
*	
* You should have received a copy of the GNU General Public License along 
* with this program; if not, write to the Free Software Foundation, Inc., 
* 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
*
*/
package org.doubango.imsdroid.Services.Impl;

import java.nio.ByteBuffer;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.doubango.imsdroid.CustomDialog;
import org.doubango.imsdroid.R;
import org.doubango.imsdroid.Model.Configuration;
import org.doubango.imsdroid.Model.HistorySMSEvent;
import org.doubango.imsdroid.Model.Configuration.CONFIGURATION_ENTRY;
import org.doubango.imsdroid.Model.Configuration.CONFIGURATION_SECTION;
import org.doubango.imsdroid.Model.HistoryEvent.StatusType;
import org.doubango.imsdroid.Screens.ScreenAV;
import org.doubango.imsdroid.Screens.ScreenNetwork;
import org.doubango.imsdroid.Services.IConfigurationService;
import org.doubango.imsdroid.Services.INetworkService;
import org.doubango.imsdroid.Services.ISipService;
import org.doubango.imsdroid.events.CallEventArgs;
import org.doubango.imsdroid.events.CallEventTypes;
import org.doubango.imsdroid.events.EventHandler;
import org.doubango.imsdroid.events.ICallEventHandler;
import org.doubango.imsdroid.events.IRegistrationEventHandler;
import org.doubango.imsdroid.events.ISubscriptionEventHandler;
import org.doubango.imsdroid.events.RegistrationEventArgs;
import org.doubango.imsdroid.events.RegistrationEventTypes;
import org.doubango.imsdroid.events.SubscriptionEventArgs;
import org.doubango.imsdroid.events.SubscriptionEventTypes;
import org.doubango.imsdroid.media.MediaType;
import org.doubango.imsdroid.sip.MyAVSession;
import org.doubango.imsdroid.sip.MyPublicationSession;
import org.doubango.imsdroid.sip.MyRegistrationSession;
import org.doubango.imsdroid.sip.MySipStack;
import org.doubango.imsdroid.sip.MySubscriptionSession;
import org.doubango.imsdroid.sip.PresenceStatus;
import org.doubango.imsdroid.sip.MySipStack.STACK_STATE;
import org.doubango.imsdroid.sip.MySubscriptionSession.EVENT_PACKAGE_TYPE;
import org.doubango.imsdroid.utils.ContentType;
import org.doubango.imsdroid.utils.StringUtils;
import org.doubango.imsdroid.utils.UriUtils;
import org.doubango.tinyWRAP.CallEvent;
import org.doubango.tinyWRAP.CallSession;
import org.doubango.tinyWRAP.DDebugCallback;
import org.doubango.tinyWRAP.DialogEvent;
import org.doubango.tinyWRAP.MessagingEvent;
import org.doubango.tinyWRAP.MessagingSession;
import org.doubango.tinyWRAP.OptionsEvent;
import org.doubango.tinyWRAP.OptionsSession;
import org.doubango.tinyWRAP.PublicationEvent;
import org.doubango.tinyWRAP.RPData;
import org.doubango.tinyWRAP.RegistrationEvent;
import org.doubango.tinyWRAP.SMSEncoder;
import org.doubango.tinyWRAP.SipCallback;
import org.doubango.tinyWRAP.SipMessage;
import org.doubango.tinyWRAP.SipSession;
import org.doubango.tinyWRAP.SipStack;
import org.doubango.tinyWRAP.StackEvent;
import org.doubango.tinyWRAP.SubscriptionEvent;
import org.doubango.tinyWRAP.SubscriptionSession;
import org.doubango.tinyWRAP.tinyWRAPConstants;
import org.doubango.tinyWRAP.tsip_invite_event_type_t;
import org.doubango.tinyWRAP.tsip_message_event_type_t;
import org.doubango.tinyWRAP.tsip_options_event_type_t;
import org.doubango.tinyWRAP.tsip_subscribe_event_type_t;

import android.content.DialogInterface;
import android.os.ConditionVariable;
import android.util.Log;

public class SipService extends Service 
implements ISipService, tinyWRAPConstants {

	private final static String TAG = SipService.class.getCanonicalName();

	// Services
	private final IConfigurationService configurationService;
	private final INetworkService networkService;

	// Event Handlers
	private final CopyOnWriteArrayList<IRegistrationEventHandler> registrationEventHandlers;
	private final CopyOnWriteArrayList<ISubscriptionEventHandler> subscriptionEventHandlers;
	private final CopyOnWriteArrayList<ICallEventHandler> callEventHandlers;

	private byte[] reginfo;
	private byte[] winfo;
	
	private MySipStack sipStack;
	private final MySipCallback sipCallback;
	
	private MyRegistrationSession regSession;
	private MySubscriptionSession subReg;
	private MySubscriptionSession subWinfo;
	private MySubscriptionSession subMwi;
	private MySubscriptionSession subDebug;
	private MyPublicationSession pubPres;
	private final CopyOnWriteArrayList<MySubscriptionSession> subPres;
	
	private final SipPrefrences preferences;
	private final DDebugCallback debugCallback;

	private ConditionVariable condHack;
	
	private static int SMS_MR = 0;

	public SipService() {
		super();

		this.sipCallback = new MySipCallback(this);
		// FIXME: to be set to null in the release version
		this.debugCallback = new DDebugCallback();

		this.registrationEventHandlers = new CopyOnWriteArrayList<IRegistrationEventHandler>();
		this.subscriptionEventHandlers = new CopyOnWriteArrayList<ISubscriptionEventHandler>();
		this.callEventHandlers = new CopyOnWriteArrayList<ICallEventHandler>();

		this.configurationService = ServiceManager.getConfigurationService();
		this.networkService = ServiceManager.getNetworkService();
		
		this.subPres = new CopyOnWriteArrayList<MySubscriptionSession>();
		
		this.preferences = new SipPrefrences();
	}

	public boolean start() {
		return true;
	}

	public boolean stop() {
		if(this.sipStack != null && this.sipStack.getState() == STACK_STATE.STARTED){
			this.sipStack.stop();
		}
		return true;
	}

	public boolean isRegistered() {
		if (this.regSession != null) {
			return this.regSession.isConnected();
		}
		return false;
	}

	public MySipStack getStack(){
		return this.sipStack;
	}
	
	public byte[] getReginfo(){
		return this.reginfo;
	}
	
	public byte[] getWinfo(){
		return this.winfo;
	}
	
	public MySubscriptionSession createPresenceSession(String toUri, EVENT_PACKAGE_TYPE eventPackage){
		MySubscriptionSession session = new MySubscriptionSession(this.sipStack, toUri, eventPackage);
		this.subPres.add(session);
		return session;
	}
	
	public void clearPresenceSessions(){
		for(MySubscriptionSession session : this.subPres){
			if(session.isConnected()){
				session.unsubscribe();
			}
		}
		//this.subPres.clear();
	}
	
	public void removePresenceSession(MySubscriptionSession session){
		if(session.isConnected()){
			session.unsubscribe();
		}
		//this.subPres.remove(session);
	}
	
	/* ===================== SIP functions ======================== */

	public boolean stopStack(){
		if(this.sipStack != null){
			return this.sipStack.stop();
		}
		return true;
	}
	
	public boolean register() {
		this.preferences.realm = this.configurationService.getString(
				CONFIGURATION_SECTION.NETWORK, CONFIGURATION_ENTRY.REALM,
				Configuration.DEFAULT_REALM);
		this.preferences.impi = this.configurationService.getString(
				CONFIGURATION_SECTION.IDENTITY, CONFIGURATION_ENTRY.IMPI,
				Configuration.DEFAULT_IMPI);
		this.preferences.impu = this.configurationService.getString(
				CONFIGURATION_SECTION.IDENTITY, CONFIGURATION_ENTRY.IMPU,
				Configuration.DEFAULT_IMPU);

		Log.i(this.getClass().getCanonicalName(), String.format(
				"realm=%s, impu=%s, impi=%s", this.preferences.realm, this.preferences.impu, this.preferences.impi));

		if (this.sipStack == null) {
			this.sipStack = new MySipStack(this.sipCallback, this.preferences.realm, this.preferences.impi, this.preferences.impu);	
			this.sipStack.setDebugCallback(this.debugCallback);
			SipStack.setCodecs_2(this.configurationService.getInt(CONFIGURATION_SECTION.MEDIA, 
	        		CONFIGURATION_ENTRY.CODECS, Configuration.DEFAULT_MEDIA_CODECS));
		} else {
			if (!this.sipStack.setRealm(this.preferences.realm)) {
				Log.e(this.getClass().getCanonicalName(), "Failed to set realm");
				return false;
			}
			if (!this.sipStack.setIMPI(this.preferences.impi)) {
				Log.e(this.getClass().getCanonicalName(), "Failed to set IMPI");
				return false;
			}
			if (!this.sipStack.setIMPU(this.preferences.impu)) {
				Log.e(this.getClass().getCanonicalName(), "Failed to set IMPU");
				return false;
			}
		}

		// set the password
		this.sipStack.setPassword(this.configurationService.getString(
				CONFIGURATION_SECTION.IDENTITY, CONFIGURATION_ENTRY.PASSWORD,
				null));
		
		// Set AMF
		this.sipStack.setAMF(this.configurationService.getString(
				CONFIGURATION_SECTION.SECURITY, CONFIGURATION_ENTRY.IMSAKA_AMF,
				Configuration.DEFAULT_IMSAKA_AMF));
		
		// Set Operator Id
		this.sipStack.setOperatorId(this.configurationService.getString(
				CONFIGURATION_SECTION.SECURITY, CONFIGURATION_ENTRY.IMSAKA_OPID,
				Configuration.DEFAULT_IMSAKA_OPID));
		
		// Check stack validity
		if (!this.sipStack.isValid()) {
			Log.e(this.getClass().getCanonicalName(), "Trying to use invalid stack");
			return false;
		}

		// Set STUN information
		if(this.configurationService.getBoolean(CONFIGURATION_SECTION.NATT, CONFIGURATION_ENTRY.USE_STUN, Configuration.DEFAULT_NATT_USE_STUN)){			
			if(this.configurationService.getBoolean(CONFIGURATION_SECTION.NATT, CONFIGURATION_ENTRY.STUN_DISCO, Configuration.DEFAULT_NATT_STUN_DISCO)){
				String domain = this.preferences.realm.substring(this.preferences.realm.indexOf(':')+1);
				int []port = new int[1];
				String server = this.sipStack.dnsSrv(String.format("_stun._udp.%s", domain), port);
				if(server == null){
					ServiceManager.getScreenService().setProgressInfoText("STUN discovery has failed");
				}
				this.sipStack.setSTUNServer(server, port[0]);// Needed event if null
			}
			else{
				String server = this.configurationService.getString(CONFIGURATION_SECTION.NATT, CONFIGURATION_ENTRY.STUN_SERVER, Configuration.DEFAULT_NATT_STUN_SERVER);
				int port = this.configurationService.getInt(CONFIGURATION_SECTION.NATT, CONFIGURATION_ENTRY.STUN_PORT, Configuration.DEFAULT_NATT_STUN_PORT);
				this.sipStack.setSTUNServer(server, port);
			}
		}
		else{
			this.sipStack.setSTUNServer(null, 0);
		}
		
		// Set Proxy-CSCF
		this.preferences.pcscf_host = this.configurationService.getString(
				CONFIGURATION_SECTION.NETWORK, CONFIGURATION_ENTRY.PCSCF_HOST,
				null); // null will trigger DNS NAPTR+SRV
		this.preferences.pcscf_port = this.configurationService.getInt(
				CONFIGURATION_SECTION.NETWORK, CONFIGURATION_ENTRY.PCSCF_PORT,
				Configuration.DEFAULT_PCSCF_PORT);
		this.preferences.transport = this.configurationService.getString(
				CONFIGURATION_SECTION.NETWORK, CONFIGURATION_ENTRY.TRANSPORT,
				Configuration.DEFAULT_TRANSPORT);
		this.preferences.ipversion = this.configurationService.getString(
				CONFIGURATION_SECTION.NETWORK, CONFIGURATION_ENTRY.IP_VERSION,
				Configuration.DEFAULT_IP_VERSION);

		Log.i(this.getClass().getCanonicalName(), String.format(
				"pcscf-host=%s, pcscf-port=%d, transport=%s, ipversion=%s",
				this.preferences.pcscf_host, this.preferences.pcscf_port, this.preferences.transport, this.preferences.ipversion));

		if (!this.sipStack.setProxyCSCF(this.preferences.pcscf_host, this.preferences.pcscf_port, this.preferences.transport,
				this.preferences.ipversion)) {
			Log.e(this.getClass().getCanonicalName(), "Failed to set Proxy-CSCF parameters");
			return false;
		}

		// Set local IP (If your reusing this code on non-Android platforms, let
		// doubango retrieve the best IP address)
		boolean ipv6 = StringUtils.equals(this.preferences.ipversion, "ipv6", true);
		if ((this.preferences.localIP = this.networkService.getLocalIP(ipv6)) == null) {
			this.preferences.localIP = ipv6 ? "::" : "10.0.2.15"; /* Probably on the emulator */
		}
		if (!this.sipStack.setLocalIP(this.preferences.localIP)) {
			Log.e(this.getClass().getCanonicalName(), "Failed to set the local IP");
			return false;
		}

		// Whether to use DNS NAPTR+SRV for the Proxy-CSCF discovery (even if the DNS requests are sent only when the stack starts,
		// should be done after setProxyCSCF())
		String discoverType = this.configurationService.getString(CONFIGURATION_SECTION.NETWORK, CONFIGURATION_ENTRY.PCSCF_DISCOVERY, Configuration.PCSCF_DISCOVERY_NONE);
		this.sipStack.setDnsDiscovery(StringUtils.equals(discoverType, Configuration.PCSCF_DISCOVERY_DNS, true));		
		
		// enable/disable 3GPP early IMS
		this.sipStack.setEarlyIMS(this.configurationService.getBoolean(
				CONFIGURATION_SECTION.NETWORK, CONFIGURATION_ENTRY.EARLY_IMS,
				Configuration.DEFAULT_EARLY_IMS));
		
		// SigComp (only update compartment Id if changed)
		if(this.configurationService.getBoolean(CONFIGURATION_SECTION.NETWORK, CONFIGURATION_ENTRY.SIGCOMP, Configuration.DEFAULT_SIGCOMP)){
			String compId = String.format("urn:uuid:%s", UUID.randomUUID().toString());
			this.sipStack.setSigCompId(compId);
		}
		else{
			this.sipStack.setSigCompId(null);
		}

		// Start the Stack
		if (!this.sipStack.start()) {
			Log.e(this.getClass().getCanonicalName(),
					"Failed to start the SIP stack");
			return false;
		}
		
		// Preference values
		this.preferences.xcap_enabled = this.configurationService.getBoolean(
				CONFIGURATION_SECTION.XCAP, CONFIGURATION_ENTRY.ENABLED,
				Configuration.DEFAULT_XCAP_ENABLED);
		this.preferences.presence_enabled = this.configurationService.getBoolean(
				CONFIGURATION_SECTION.RCS, CONFIGURATION_ENTRY.PRESENCE,
				Configuration.DEFAULT_RCS_PRESENCE);
		this.preferences.mwi = this.configurationService.getBoolean(
				CONFIGURATION_SECTION.RCS, CONFIGURATION_ENTRY.MWI,
				Configuration.DEFAULT_RCS_MWI);
		
		// Create registration session
		if (this.regSession == null) {
			this.regSession = new MyRegistrationSession(this.sipStack);
		}
		else{
			this.regSession.setSigCompId(this.sipStack.getSigCompId());
		}
		
		// Set/update From URI. For Registration ToUri should be equals to realm
		// (done by the stack)
		this.regSession.setFromUri(this.preferences.impu);
		/* this.regSession.setToUri(this.preferences.impu); */

		/* Before registering, check if AoR hacking id enabled */
		this.preferences.hackAoR = this.configurationService.getBoolean(
				CONFIGURATION_SECTION.NATT, CONFIGURATION_ENTRY.HACK_AOR,
				Configuration.DEFAULT_NATT_HACK_AOR);
		if (this.preferences.hackAoR) {
			if (this.condHack == null) {
				this.condHack = new ConditionVariable();
			}
			final OptionsSession optSession = new OptionsSession(this.sipStack);
			// optSession.setToUri(String.format("sip:%s@%s", "hacking_the_aor", this.preferences.realm));
			optSession.send();
			try {
				synchronized (this.condHack) {
					this.condHack.wait(this.configurationService.getInt(
							CONFIGURATION_SECTION.NATT,
							CONFIGURATION_ENTRY.HACK_AOR_TIMEOUT,
							Configuration.DEFAULT_NATT_HACK_AOR_TIMEOUT));
				}
			} catch (InterruptedException e) {
				Log.e(SipService.TAG, e.getMessage());
			}
			this.condHack = null;
			optSession.delete();
		}

		if (!this.regSession.register()) {
			Log.e(SipService.TAG, "Failed to send REGISTER request");
			return false;
		}

		return true;
	}

	public boolean unregister() {
		if (this.isRegistered()) {
			new Thread(new Runnable(){
				@Override
				public void run() {
					SipService.this.sipStack.stop();
				}
			}).start();
		}
		Log.d(this.getClass().getCanonicalName(), "Already unregistered");
		return true;
	}
	
	public boolean publish(){
		if(!this.isRegistered() || (this.pubPres == null)){
			return false;
		}
		
		if(!this.preferences.presence_enabled){
			return true; // silently ignore
		}
		
		String freeText = this.configurationService.getString(CONFIGURATION_SECTION.RCS, CONFIGURATION_ENTRY.FREE_TEXT, Configuration.DEFAULT_RCS_FREE_TEXT);
		PresenceStatus status = Enum.valueOf(PresenceStatus.class, this.configurationService.getString(
				CONFIGURATION_SECTION.RCS,
				CONFIGURATION_ENTRY.STATUS,
				Configuration.DEFAULT_RCS_STATUS.toString()));
		return this.pubPres.publish(status, freeText);
	}

	public boolean sendSMS(byte[] content, String remoteUri, String contentType){
		if(!this.isRegistered() || content == null){
			return false;
		}
		
		final boolean ret;
		final MessagingSession session = new MessagingSession(this.sipStack);
		final boolean binarySMS = this.configurationService.getBoolean(CONFIGURATION_SECTION.RCS, CONFIGURATION_ENTRY.BINARY_SMS, Configuration.DEFAULT_RCS_BINARY_SMS);
		final String SMSC = this.configurationService.getString(CONFIGURATION_SECTION.RCS, CONFIGURATION_ENTRY.SMSC, Configuration.DEFAULT_RCS_SMSC);
		final String SMSCPhoneNumber;
		final String dstPhoneNumber;
		
		if(this.sipStack.getSigCompId() != null){
			session.addSigCompCompartment(this.sipStack.getSigCompId());
		}
		
		if(binarySMS && (SMSCPhoneNumber = UriUtils.getValidPhoneNumber(SMSC)) != null && (dstPhoneNumber = UriUtils.getValidPhoneNumber(remoteUri)) != null){
			session.setToUri(SMSC);
			session.addHeader("Content-Type", "application/vnd.3gpp.sms");
			session.addHeader("Transfer-Encoding", "binary");
			
			RPData rpdata = SMSEncoder.encodeSubmit(++SipService.SMS_MR, SMSCPhoneNumber, dstPhoneNumber, new String(content));
			long payloadLength = rpdata.getPayloadLength();
			final ByteBuffer payload = ByteBuffer.allocateDirect((int)payloadLength);
			payloadLength = rpdata.getPayload(payload, payload.capacity());
			ret = session.send(payload, payloadLength);
			
			if(SipService.SMS_MR >= 255){
				SipService.SMS_MR = 0;
			}
		}
		else{
			session.setToUri(remoteUri);
			session.addHeader("Content-Type", contentType);
			
			final ByteBuffer payload = ByteBuffer.allocateDirect(content.length);
			payload.put(content);
			ret = session.send(payload, content.length);
		}
		session.delete();
		
		return ret;
	}
	
	/* ===================== Add/Remove handlers ======================== */

	@Override
	public boolean addRegistrationEventHandler(IRegistrationEventHandler handler) {
		return EventHandler.addEventHandler(this.registrationEventHandlers, handler);
	}

	@Override
	public boolean removeRegistrationEventHandler(IRegistrationEventHandler handler) {
		return EventHandler.removeEventHandler(this.registrationEventHandlers, handler);
	}

	@Override
	public boolean addSubscriptionEventHandler(ISubscriptionEventHandler handler) {
		return EventHandler.addEventHandler(this.subscriptionEventHandlers, handler);
	}

	@Override
	public boolean removeSubscriptionEventHandler(ISubscriptionEventHandler handler) {
		return EventHandler.removeEventHandler(this.subscriptionEventHandlers, handler);
	}
	
	@Override
	public boolean addCallEventHandler(ICallEventHandler handler) {
		return EventHandler.addEventHandler(this.callEventHandlers, handler);
	}

	@Override
	public boolean removeCallEventHandler(ICallEventHandler handler) {
		return EventHandler.removeEventHandler(this.callEventHandlers, handler);
	}

	/* ===================== Dispatch events ======================== */
	private synchronized void onRegistrationEvent(final RegistrationEventArgs eargs) {
		for(int i = 0; i<this.registrationEventHandlers.size(); i++){
			final IRegistrationEventHandler handler = this.registrationEventHandlers.get(i);
			new Thread(new Runnable() {
				public void run() {
					if (!handler.onRegistrationEvent(this, eargs)) {
						Log.w(handler.getClass().getName(), "onRegistrationEvent failed");
					}
				}
			}).start();
		}
	}
	
	private synchronized void onSubscriptionEvent(final SubscriptionEventArgs eargs) {
		for(int i = 0; i<this.subscriptionEventHandlers.size(); i++){
			final ISubscriptionEventHandler handler = this.subscriptionEventHandlers.get(i);
			new Thread(new Runnable() {
				public void run() {
					if (!handler.onSubscriptionEvent(this, eargs)) {
						Log.w(handler.getClass().getName(), "onSubscriptionEvent failed");
					}
				}
			}).start();
		}
	}
	
	private synchronized void onCallEvent(final CallEventArgs eargs) {
		for(int i = 0; i<this.callEventHandlers.size(); i++){
			final ICallEventHandler handler = this.callEventHandlers.get(i);
			new Thread(new Runnable() {
				public void run() {
					if (!handler.onCallEvent(this, eargs)) {
						Log.w(handler.getClass().getName(), "onCallEvent failed");
					}
				}
			}).start();
		}
	}

	

	/* ===================== Private functions ======================== */
	private void doPostRegistrationOp()
	{
		// guard
		if(!this.isRegistered()){
			return;
		}
		
		Log.d(SipService.TAG, "Doing post registration operations");
		
		/*
		 * 3GPP TS 24.229 5.1.1.3 Subscription to registration-state event package
		 * Upon receipt of a 2xx response to the initial registration, the UE shall subscribe to the reg event package for the public
		 * user identity registered at the user's registrar (S-CSCF) as described in RFC 3680 [43].
		 */
		if(this.subReg == null){
			this.subReg = new MySubscriptionSession(this.sipStack, this.preferences.impu, EVENT_PACKAGE_TYPE.REG);
		}
		else{
			this.subReg.setToUri(this.preferences.impu);
			this.subReg.setFromUri(this.preferences.impu);
		}
		this.subReg.subscribe();		
		
		// Message Waiting Indication
		if(this.preferences.mwi){
			if(this.subMwi == null){
				this.subMwi = new MySubscriptionSession(this.sipStack, this.preferences.impu, EVENT_PACKAGE_TYPE.MESSAGE_SUMMARY); 
			}
			else{
				this.subMwi.setToUri(this.preferences.impu);
				this.subMwi.setFromUri(this.preferences.impu);
				this.subMwi.setSigCompId(this.sipStack.getSigCompId());
			}
			this.subMwi.subscribe();
		}
		
		// Presence
		if(this.preferences.presence_enabled){
			// Subscribe to "watcher-info" and "presence"
			if(this.preferences.xcap_enabled){
				// "watcher-info"
				if(this.subWinfo == null){
					this.subWinfo = new MySubscriptionSession(this.sipStack, this.preferences.impu, EVENT_PACKAGE_TYPE.WINFO); 
				}
				else{
					this.subWinfo.setToUri(this.preferences.impu);
					this.subWinfo.setFromUri(this.preferences.impu);
					this.subMwi.setSigCompId(this.sipStack.getSigCompId());
				}
				this.subWinfo.subscribe();
				// "eventlist"
			}
			else{
				
			}
			
			// Publish presence
			if(this.pubPres == null){
				this.pubPres = new MyPublicationSession(this.sipStack, this.preferences.impu);
			}
			else{
				this.pubPres.setFromUri(this.preferences.impu);
				this.pubPres.setToUri(this.preferences.impu);
				this.subMwi.setSigCompId(this.sipStack.getSigCompId());
			}
			
			String freeText = this.configurationService.getString(CONFIGURATION_SECTION.RCS, CONFIGURATION_ENTRY.FREE_TEXT, Configuration.DEFAULT_RCS_FREE_TEXT);
			PresenceStatus status = Enum.valueOf(PresenceStatus.class, this.configurationService.getString(
					CONFIGURATION_SECTION.RCS,
					CONFIGURATION_ENTRY.STATUS,
					Configuration.DEFAULT_RCS_STATUS.toString()));
			this.pubPres.publish(status, freeText);
		}
	}

	/* ===================== Sip Callback ======================== */
	private class MySipCallback extends SipCallback {

		private final SipService sipService;

		private MySipCallback(SipService sipService) {
			super();

			this.sipService = sipService;
		}

		@Override
		public int OnRegistrationEvent(RegistrationEvent e) {
			return 0;
		}
		
		@Override
		public int OnPublicationEvent(PublicationEvent e) {			
			return 0;
		}

		@Override
		public int OnMessagingEvent(MessagingEvent e){			
			final tsip_message_event_type_t type = e.getType();
			
			switch(type){
				case tsip_ao_message:
					/* String phrase = e.getPhrase(); */
					/* short code = e.getCode(); */
					break;
				case tsip_i_message:
					final SipMessage message = e.getSipMessage();
					if(message == null){
						return 0;
					}
					final String from = message.getSipHeaderValue("f");
					/* final String contentType = message.getSipHeaderValue("c"); */
					final byte[] content = message.getSipContent();
					HistorySMSEvent event = new HistorySMSEvent(from);
					event.setStatus(StatusType.Incoming);
					event.setContent(new String(content));
					ServiceManager.getHistoryService().addEvent(event);
					ServiceManager.showSMSNotif(R.drawable.sms_into_16, "New SMS");
					ServiceManager.getSoundService().playNewSMS();
					
					break;
			}
			
			return 0;
		}
		
		@Override
		public int OnSubscriptionEvent(SubscriptionEvent e) {
			final tsip_subscribe_event_type_t type = e.getType();
			final SubscriptionSession session = e.getSession();
			
			if(session == null){
				return 0;
			}
			
			switch(type){
				case tsip_ao_subscribe:					
				case tsip_ao_unsubscribe:
					break;
					
				case tsip_i_notify:
					final short code = e.getCode();
					final String phrase = e.getPhrase();
					final SipMessage message = e.getSipMessage();
					if(message == null){
						return 0;
					}
					final String contentType = message.getSipHeaderValue("c");
					final byte[] content = message.getSipContent();
					
					if(content != null){
						if(StringUtils.equals(contentType, ContentType.REG_INFO, true)){
							this.sipService.reginfo = content;
						}
						else if(StringUtils.equals(contentType, ContentType.WATCHER_INFO, true)){
							this.sipService.winfo = content;
						}
						
						SubscriptionEventArgs eargs = new SubscriptionEventArgs(SubscriptionEventTypes.INCOMING_NOTIFY, 
								code, phrase, content, contentType);
						eargs.putExtra("session", session);
						this.sipService.onSubscriptionEvent(eargs);
					}
					break;
				}
			
			return 0;
		}

		@Override
		public int OnDialogEvent(DialogEvent e){
			final String phrase = e.getPhrase();
			final short code = e.getCode();
			SipSession session = e.getBaseSession();
			
			if(session == null){
				return 0;
			}
			
			Log.d(SipService.TAG, String.format("OnDialogEvent (%s)", phrase));
			
			switch(code){
				case tsip_event_code_dialog_connecting:
					// Registration
					if((this.sipService.regSession != null) && (session.getId() == this.sipService.regSession.getId())){							
						this.sipService.onRegistrationEvent(new RegistrationEventArgs(
									RegistrationEventTypes.REGISTRATION_INPROGRESS, code, phrase));
					}
					// Audio/Video Calls
					if(MyAVSession.getSession(session.getId()) != null){
						this.sipService.onCallEvent(new CallEventArgs(session.getId(), CallEventTypes.INPROGRESS, phrase)); 
					}
					// Subscription
					// Publication
					// ...
					break;
					
				case tsip_event_code_dialog_connected:
					// Registration
					if((this.sipService.regSession != null) && (session.getId() == this.sipService.regSession.getId())){
						this.sipService.regSession.setConnected(true);
						new Thread(new Runnable(){
							public void run() {
								SipService.this.doPostRegistrationOp();
							}
						}).start();
						
						this.sipService.onRegistrationEvent(new RegistrationEventArgs(
									RegistrationEventTypes.REGISTRATION_OK, code, phrase));
					}
					// Presence Publication
					else if((this.sipService.pubPres != null) && (session.getId() == this.sipService.pubPres.getId())){							
						this.sipService.pubPres.setConnected(true);
					}
					// Audio/Video Calls
					else if(MyAVSession.getSession(session.getId()) != null){
						this.sipService.onCallEvent(new CallEventArgs(session.getId(), CallEventTypes.CONNECTED, phrase)); 
					}
					// Publication
					// Subscription
					else{
						for(MySubscriptionSession s : this.sipService.subPres){
							if(s.getId() == session.getId()){
								s.setConnected(true);
								SubscriptionEventArgs eargs = new SubscriptionEventArgs(SubscriptionEventTypes.SUBSCRIPTION_OK, 
										code, phrase, null, "null");
								eargs.putExtra("session", s);
								this.sipService.onSubscriptionEvent(eargs);
							}
						}
					}
					//..
					break;
					
				case tsip_event_code_dialog_terminating:
					// Registration
					if((this.sipService.regSession != null) && (session.getId() == this.sipService.regSession.getId())){						
						this.sipService.onRegistrationEvent(new RegistrationEventArgs(
									RegistrationEventTypes.UNREGISTRATION_INPROGRESS, code, phrase));
					}
					// Subscription
					// Publication
					// ...
					break;
				
				case tsip_event_code_dialog_terminated:
					if((this.sipService.regSession != null) && (session.getId() == this.sipService.regSession.getId())){
						this.sipService.regSession.setConnected(false);
						this.sipService.onRegistrationEvent(new RegistrationEventArgs(
									RegistrationEventTypes.UNREGISTRATION_OK, code, phrase));
						/* Stop the stack (as we are already in the stack-thread, then do it in a new thread) */
						new Thread(new Runnable(){
							public void run() {	
								if(SipService.this.sipStack.getState() == STACK_STATE.STARTED){
									SipService.this.sipStack.stop();
								}
							}
						}).start();
					}
					// Presence Publication
					else if((this.sipService.pubPres != null) && (session.getId() == this.sipService.pubPres.getId())){							
						this.sipService.pubPres.setConnected(false);
					}
					// Audio/Video Calls
					if(MyAVSession.getSession(session.getId()) != null){
						this.sipService.onCallEvent(new CallEventArgs(session.getId(), CallEventTypes.DISCONNECTED, phrase)); 
					}
					// Publication
					// Subscription
					else{
						for(MySubscriptionSession s : this.sipService.subPres){
							if(s.getId() == session.getId()){
								SubscriptionEventArgs eargs = new SubscriptionEventArgs(SubscriptionEventTypes.UNSUBSCRIPTION_OK, 
										code, phrase, null, "null");
								s.setConnected(false);
								eargs.putExtra("session", s);
								this.sipService.onSubscriptionEvent(eargs);
								this.sipService.subPres.remove(s);
							}
						}
					}
					// ...
					break;
					
				default:
					break;
			}
			
			return 0;
		}	
		
		@Override
		public int OnStackEvent(StackEvent e) {
			//final String phrase = e.getPhrase();
			final short code = e.getCode();
			switch(code){
				case tsip_event_code_stack_started:
					this.sipService.sipStack.setState(STACK_STATE.STARTED);
					Log.d(SipService.TAG, "Stack started");
					break;
				case tsip_event_code_stack_failed_to_start:
					final String phrase = e.getPhrase();
					ServiceManager.getScreenService().runOnUiThread(new Runnable() {
						@Override
						public void run() {
							CustomDialog.show(ServiceManager.getMainActivity(), R.drawable.delete_48, "Failed to start the IMS stack", 
									String.format("\nPlease check your connection information. \nAdditional info:\n%s", phrase),
											"OK", new DialogInterface.OnClickListener(){
												@Override
												public void onClick(DialogInterface dialog, int which) {
												}
									}, null, null);
						}
					});
										
					Log.e(SipService.TAG, "Failed to start the stack");
					break;
				case tsip_event_code_stack_failed_to_stop:
					Log.e(SipService.TAG, "Failed to stop the stack");
					break;
				case tsip_event_code_stack_stopped:
					this.sipService.sipStack.setState(STACK_STATE.STOPPED);
					Log.d(SipService.TAG, "Stack stoped");
					break;
			}
			return 0;
		}

		@Override
		public int OnCallEvent(CallEvent e) {
			//short code = e.getCode();
			String phrase = e.getPhrase();
			tsip_invite_event_type_t type = e.getType();
			//SipMessage message = e.getSipMessage();
			CallSession session = e.getSession();

			switch(type){
				case tsip_i_newcall:
					if (session != null){ /* As we are not the owner, then the session MUST be null */
                        Log.e(SipService.TAG, "Invalid incoming session");
                        session.hangup();
                        return 0;
                    }
                    else if ((session = e.takeSessionOwnership()) != null){
                    	SipMessage message = e.getSipMessage();
                    	if(message != null){                    		
                    		final String from = message.getSipHeaderValue("f");
                    		final MyAVSession avSession = MyAVSession.takeIncomingSession(this.sipService.sipStack, session);
	                    		                    	
	                    	ServiceManager.getScreenService().runOnUiThread(new Runnable(){
								@Override
								public void run() {
									ScreenAV.receiveCall(avSession, from, MediaType.AudioVideo);
								}
	                    	});
	                    	
	                    	CallEventArgs eargs = new CallEventArgs(avSession.getId(), CallEventTypes.INCOMING, phrase);
	                    	eargs.putExtra("from", from);
	                    	this.sipService.onCallEvent(eargs);
                    	}
                    	else{
                    		 Log.e(SipService.TAG, "Invalid SIP message");
                    	}
                    }
					break;
				case tsip_i_request:
				case tsip_ao_request:
				case tsip_o_ect_ok:
				case tsip_o_ect_nok:
				case tsip_i_ect:
					break;
				case tsip_m_early_media:
					this.sipService.onCallEvent(new CallEventArgs(session.getId(), CallEventTypes.EARLY_MEDIA, phrase));
					break;
				case tsip_m_local_hold_ok:
					this.sipService.onCallEvent(new CallEventArgs(session.getId(), CallEventTypes.LOCAL_HOLD_OK, phrase));
					break;
				case tsip_m_local_hold_nok:
					this.sipService.onCallEvent(new CallEventArgs(session.getId(), CallEventTypes.LOCAL_HOLD_NOK, phrase));
					break;
				case tsip_m_local_resume_ok:
					this.sipService.onCallEvent(new CallEventArgs(session.getId(), CallEventTypes.LOCAL_RESUME_OK, phrase));
					break;
				case tsip_m_local_resume_nok:
					this.sipService.onCallEvent(new CallEventArgs(session.getId(), CallEventTypes.LOCAL_RESUME_NOK, phrase));
					break;
				case tsip_m_remote_hold:
					this.sipService.onCallEvent(new CallEventArgs(session.getId(), CallEventTypes.REMOTE_HOLD, phrase));
					break;
				case tsip_m_remote_resume:
					this.sipService.onCallEvent(new CallEventArgs(session.getId(), CallEventTypes.REMOTE_RESUME, phrase));
					break;
			}
			return 0;
		}
		
		@Override
		public int OnOptionsEvent(OptionsEvent e) {
			//short code = e.getCode();
			tsip_options_event_type_t type = e.getType();
			//OptionsSession session = e.getSession();
			SipMessage message = e.getSipMessage();

			if (message == null) {
				return 0;
			}

			switch (type) {
			case tsip_ao_options:
				String rport = message.getSipHeaderParamValue("v", "rport");
				String received = message.getSipHeaderParamValue("v","received");
				if (rport == null || rport.equals("0")) { // FIXME: change tsip_header_Via_get_special_param_value() to return "tsk_null" instead of "0"
					rport = message.getSipHeaderParamValue("v", "received_port_ext");
				}
				if (SipService.this.condHack != null && SipService.this.preferences.hackAoR) {
					SipService.this.sipStack.setAoR(received, Integer.parseInt(rport));
					SipService.this.condHack.open();
				}
				break;
			case tsip_i_options:
			default:
				break;
			}

			return 0;
		}
	}

	/* ===================== Sip Session Preferences ======================== */
	private class SipPrefrences {
		private boolean rcs;
		private boolean xcapdiff;
		private boolean xcap_enabled;
		private boolean preslist;
		private boolean deferredMsg;
		private boolean presence_enabled;
		private boolean mwi;
		private String impi;
		private String impu;
		private String realm;
		private String pcscf_host;
		private int pcscf_port;
		private String transport;
		private String ipversion;
		private String localIP;
		private boolean hackAoR;

		private SipPrefrences() {

		}
	}
}
