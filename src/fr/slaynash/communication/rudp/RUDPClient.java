package fr.slaynash.communication.rudp;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class RUDPClient {//TODO remove use of ByteBuffers and use functions instead
	
	private ClientType type = ClientType.NORMAL_CLIENT;
	private RUDPServer server = null;
	
	InetAddress address;
	int port;
	long lastPacketReceiveTime;
	public ConnectionState state = ConnectionState.STATE_DISCONNECTED;
	private DatagramSocket socket = null;
	private ClientManager clientManager = null;
	private Thread reliableThread = null;
	private Thread receiveThread = null;
	private Thread pingThread = null;
	
	private List<Long> packetsReceived = new ArrayList<Long>();
	private List<ReliablePacket> packetsSent = Collections.synchronizedList(new ArrayList<ReliablePacket>());
	private int latency = 400;
	private RUDPClient instance = this;
	int sent;
	int sentReliable;
	int received;
	int receivedReliable;

	public RUDPClient(InetAddress address, int port) throws SocketException{
		this.address = address;
		this.port = port;
		
		socket = new DatagramSocket();
		socket.setSoTimeout(Values.CLIENT_TIMEOUT_TIME);
		
		lastPacketReceiveTime = System.nanoTime();
	}
	
	RUDPClient(InetAddress clientAddress, int clientPort, RUDPServer rudpServer, Class<? extends ClientManager> clientManager) {
		this.address = clientAddress;
		this.port = clientPort;
		this.server = rudpServer;
		this.type = ClientType.SERVER_CHILD;
		this.sentReliable = 1;
		this.sent = 1;
		Constructor<? extends ClientManager> constructor;
		try {
			constructor = clientManager.getConstructor(RUDPClient.class);
			this.clientManager = constructor.newInstance(this);
		} catch (NoSuchMethodException | SecurityException | InstantiationException | IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
			e.printStackTrace();
		}
		
		lastPacketReceiveTime = System.nanoTime();
		
		state = ConnectionState.STATE_CONNECTING;
	}

	public void connect() throws SocketTimeoutException, SocketException, UnknownHostException, IOException{
		System.out.println("[RUDPClient] Connecting to UDP port "+port+"...");
		if(state == ConnectionState.STATE_CONNECTED){System.out.println("[RUDPClient] Client already connected !");return;}
		if(state == ConnectionState.STATE_CONNECTING){System.out.println("[RUDPClient] Client already connecting !");return;}
		
		state = ConnectionState.STATE_CONNECTING;
		try {
			//Send handshake packet
			byte[] handshakePacket = new byte[9];
			handshakePacket[0] = Values.commands.HANDSHAKE_START;
			BytesUtils.writeBytes(handshakePacket, 1, Values.VERSION_MAJOR);
			BytesUtils.writeBytes(handshakePacket, 5, Values.VERSION_MINOR);
			sendPacket(handshakePacket);
			
			//Receive handshake response packet
			byte[] buffer = new byte[8196];
			DatagramPacket datagramPacket = new DatagramPacket(buffer, buffer.length, address, port);
			socket.receive(datagramPacket);
			byte[] data = new byte[datagramPacket.getLength()];
			System.arraycopy(datagramPacket.getData(), datagramPacket.getOffset(), data, 0, datagramPacket.getLength());
			
			//Handle handshake response packet
			if(data[0] != Values.commands.HANDSHAKE_OK){
				
				state = ConnectionState.STATE_DISCONNECTED;
				byte[] dataText = new byte[data.length-1];
				System.arraycopy(data, 1, dataText, 0, dataText.length);
				System.err.println("[RUDPClient] Unable to connect: "+new String(dataText, "UTF-8"));//TODO throw Exception
				
			}
			else{
				
				state = ConnectionState.STATE_CONNECTED;
				initReceiveThread();
				initPingThread();
				initRelyThread();
				
				reliableThread.start();
				receiveThread.start();
				pingThread.start();
				
				System.out.println("[RUDPClient] Connected !");
				
			}
		} catch (IOException e) {
			state = ConnectionState.STATE_DISCONNECTED;
			throw e;
		}
	}
	/*
	private static String toStringRepresentation(byte[] data) {
		String rep = "";
		for(byte b:data) {
			rep+= String.format("%02X ", b);
		}
		return rep;
	}
	*/
	public void sendReliablePacket(byte[] data){
		byte[] packet = new byte[data.length+9];
		long timeNS = System.nanoTime();
		
		//byte[] dp = new byte[8];
		//BytesUtils.writeBytes(dp, 0, timeNS);
		//System.out.println("[RUDPClient] reliable packet sent "+toStringRepresentation(dp)+" - "+timeNS);
		
		packet[0] = (byte)1;
		BytesUtils.writeBytes(packet, 1, timeNS);
		System.arraycopy(data, 0, packet, 9, data.length);
		if(type == ClientType.SERVER_CHILD) server.sendPacket(packet, address, port);
		else{
			DatagramPacket dpacket = new DatagramPacket(packet, packet.length, address, port);
	        try {
	        	socket.send(dpacket);
			} catch (IOException e) {
				e.printStackTrace();
				return;
			}
		}
		synchronized(packetsSent){
			packetsSent.add(new ReliablePacket(timeNS, packet));
		}
		sentReliable++;
	}
	
	public void sendPacket(byte[] data){
		byte[] packetData = new byte[data.length+1];
		packetData[0] = (byte)0;
		System.arraycopy(data, 0, packetData, 1, data.length);
		
		if(type == ClientType.SERVER_CHILD) server.sendPacket(packetData, address, port);
		else{
			DatagramPacket packet = new DatagramPacket(packetData, packetData.length, address, port);
	        try {
	        	socket.send(packet);
			} catch (IOException e) {
				e.printStackTrace();
				return;
			}
		}
		sent++;
	}

	void handlePacket(byte[] data) {
		//Counter
		if(data[0] == Values.UNRELIABLE && data[1] != Values.commands.RELY)
			received++;
		else if(data[0] == Values.RELIABLE)
			receivedReliable++;
		
		
		lastPacketReceiveTime = System.nanoTime();
		if(data[0] == Values.UNRELIABLE && data[1] == Values.commands.RELY){
			
			//byte[] dp = new byte[] {data[2], data[3], data[4], data[5], data[6], data[7], data[8], data[9]};
			
			//System.out.println("RELY RECEIVED "+toStringRepresentation(dp)+" - "+BytesUtils.toLong(data, 2));
			synchronized(packetsSent){
				for(int i=0;i<packetsSent.size();i++) {
					if(packetsSent.get(i).dateNS == BytesUtils.toLong(data, 2)){
						packetsSent.remove(i);
						//System.out.println("FOUND AND REMOVED FROM LIST");
						return;
					}
				}
			}
			return;
		}
		else if(data[0] == Values.UNRELIABLE && data[1] == Values.commands.PING_REQUEST)
		{
			byte[] l = new byte[]{Values.commands.PING_RESPONSE, data[2], data[3], data[4], data[5], data[6], data[7], data[8], data[9]};
			sendPacket(l);//sending time received (long) // ping packet format: [IN:] Reliable CMD_PING_REPONSE sendTimeNano
			return;
		}
		else if(data[0] == Values.UNRELIABLE && data[1] == Values.commands.PING_RESPONSE)
		{
			latency = (int) ((System.nanoTime() - BytesUtils.toLong(data, 2))/1e6);
			if(latency < 5) latency = 5;
			//System.out.println("latency: "+latency+"ms");
			return;
		}
		else if(data[0] == Values.RELIABLE){
			
			//Send rely packet
			byte[] l = new byte[]{Values.commands.RELY, data[1], data[2], data[3], data[4], data[5], data[6], data[7], data[8]};
			sendPacket(l);
			
			//save to received packet list
			long bl = BytesUtils.toLong(data, 1);
			Long packetOverTime = bl-(2000000000L);//2 seconds
			int i = 0;
			while(packetsReceived.size() > i){
				long sl = packetsReceived.get(i);
				if(sl == bl){
					//System.out.println("[RUDPClient] Packet already received");
					return;
				}
				if(sl < packetOverTime) packetsReceived.remove(i);//XXX use another thread ?
				else i++;
			}
			packetsReceived.add(bl);
			
			//handle reliable packet
			if(data[9] == Values.commands.DISCONNECT){
				byte[] packetData = new byte[data.length-10];
				System.arraycopy(data, 10, packetData, 0, data.length-10);
				disconnected(new String(packetData, StandardCharsets.UTF_8));
				return;
			}
			if(clientManager != null) {
				try {
					byte[] packetData = new byte[data.length-9];
					System.arraycopy(data, 9, packetData, 0, data.length-9);
					clientManager.handleReliablePacket(packetData, bl);
				}
				catch(Exception e) {
					System.err.print("[RUDPClient] An error occured while handling reliable packet:");
					e.printStackTrace();
				}
			}
		}
		else if(data[0] == Values.UNRELIABLE && data[1] == Values.commands.PACKETSSTATS_REQUEST){
			byte[] packet = new byte[17];
			packet[0] = Values.commands.PACKETSSTATS_RESPONSE;
			BytesUtils.writeBytes(packet, 1, sent);
			BytesUtils.writeBytes(packet, 5, sentReliable);
			BytesUtils.writeBytes(packet, 9, received);
			BytesUtils.writeBytes(packet, 13, receivedReliable);
			sendPacket(packet);
		}
		else if(clientManager != null) clientManager.handlePacket(data);
	}
	
	void disconnected(String reason) {
		state = ConnectionState.STATE_DISCONNECTED;
		if(clientManager != null) clientManager.onDisconnected(reason);
	}
	
	public void disconnect(String reason) {
		if(state == ConnectionState.STATE_DISCONNECTED) return;
		byte[] reasonB = null;
		try {reasonB = reason.getBytes("UTF-8");
		} catch (UnsupportedEncodingException e) {e.printStackTrace();}
		byte[] reponse = new byte[reasonB.length+1];
		System.arraycopy(reasonB, 0, reponse, 1, reasonB.length);
		
		reponse[0] = Values.commands.DISCONNECT;
		if(type == ClientType.SERVER_CHILD){
			sendReliablePacket(reponse);
			state = ConnectionState.STATE_DISCONNECTING;
		}
		if(type == ClientType.NORMAL_CLIENT){
			sendPacket(reponse);
			state = ConnectionState.STATE_DISCONNECTED;
			socket.close();
		}
		//clientManager.disconnect(reason);
	}
	
	public void setPacketHandler(ClientManager packetHandler){
		packetHandler.rudp = this;
		this.clientManager = packetHandler;
	}

	void initialize() {
		initRelyThread();
		reliableThread.start();
		state = ConnectionState.STATE_CONNECTED;
		clientManager.initializeClient();
	}
	
	public int getLatency(){
		return latency;
	}
	
	private void sendPacketRaw(byte[] data){
		if(type == ClientType.SERVER_CHILD) server.sendPacket(data, address, port);
		else{
			DatagramPacket packet = new DatagramPacket(data, data.length, address, port);
	        try {
	        	socket.send(packet);
			} catch (IOException e) {e.printStackTrace();}
		}
	}
	
	private class ReliablePacket{
		protected long dateNS;
		protected long minDateNS;
		protected byte[] data;

		public ReliablePacket(long dateNS, byte[] data){
			this.dateNS = dateNS;
			this.minDateNS = dateNS+(latency*2*1000000L);
			this.data = data;
		}
	}
	
	public void initReceiveThread() {
		receiveThread = new Thread(new Runnable() {
			@Override
			public void run() {
				while(state == ConnectionState.STATE_CONNECTED){
					byte[] buffer = new byte[Values.RECEIVE_MAX_SIZE];
					DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
					
					try {
						socket.receive(packet);
					} catch (SocketTimeoutException e) {
						state = ConnectionState.STATE_DISCONNECTED;
						disconnected("Connection timed out");
						return;
					} catch (IOException e) {
						if(state == ConnectionState.STATE_DISCONNECTED) return;
						System.err.println("[RUDPClient] An error as occured while receiving a packet: ");
						e.printStackTrace();
					}
					byte[] data = new byte[packet.getLength()];
					System.arraycopy(packet.getData(), packet.getOffset(), data, 0, packet.getLength());
					try {
						handlePacket(data);
					}
					catch(Exception e) {
						System.err.print("[RUDPClient] An error occured while handling packet:");
						e.printStackTrace();
					}
					packet.setLength(Values.RECEIVE_MAX_SIZE);
				}
			}
		}, "RUDPClient receive thread");
	}
	
	public void initPingThread() {
		pingThread = new Thread(new Runnable() {
			@Override
			public void run() {
				try {
					while(state == ConnectionState.STATE_CONNECTED){
						byte[] pingPacket = new byte[9];
						pingPacket[0] = Values.commands.PING_REQUEST;
						BytesUtils.writeBytes(pingPacket, 1, System.nanoTime());
						sendPacket(pingPacket);
						
						Thread.sleep(Values.PING_INTERVAL);
					}
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			}
		}, "RUDPClient ping thread");
	}
	
	public void initRelyThread() {
		reliableThread = new Thread(new Runnable() {
			@Override
			public void run() {
				try {
					while(state == ConnectionState.STATE_CONNECTING || state == ConnectionState.STATE_CONNECTED || (state == ConnectionState.STATE_DISCONNECTING && !packetsSent.isEmpty())){
						synchronized(packetsSent){
							long currentNS = System.nanoTime();
							long minNS = currentNS+(latency*2*1000000L);
							int i=0;
							while(i<packetsSent.size()){
								ReliablePacket rpacket = packetsSent.get(i);
								
								//byte[] dp = new byte[8];
								//BytesUtils.writeBytes(dp, 0, rpacket.dateNS);
								
								if(rpacket.dateNS+Values.PACKET_TIMEOUT_TIME_NANOSECONDS < currentNS){
									System.out.println("[RUDPClient] Packet dropped "+rpacket.dateNS/*toStringRepresentation(dp)*/);
									packetsSent.remove(i);
									continue;
								}
								if(rpacket.minDateNS < currentNS){
									rpacket.minDateNS = minNS;
									sendPacketRaw(rpacket.data);
									//System.out.println("[RUDPClient] Sending reliable packet again "+rpacket.dateNS/*toStringRepresentation(dp)*/);
								}
								i++;
							}
						}
						Thread.sleep(20);
					}
					state = ConnectionState.STATE_DISCONNECTED;
					if(type == ClientType.SERVER_CHILD) server.remove(instance);
				} catch (InterruptedException e) {e.printStackTrace();}
			}
		}, "RUDPClient rely thread");
	}

	public int getSent() {
		return sent;
	}

	public int getSentReliable() {
		return sentReliable;
	}

	public int getReceived() {
		return received;
	}

	public int getReceivedReliable() {
		return receivedReliable;
	}
	/*
	public byte[] getByteAddress() {
		return address.getAddress();
	}

	public int getPort() {
		return port;
	}
	*/
	
}
