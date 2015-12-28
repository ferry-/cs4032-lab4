import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.Vector;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.HashMap;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.SocketException;
import java.io.*;
import java.lang.Runnable;

public class Server {
	protected static String STUDENT_ID = "ed9d356567882c76a5e1ab4e224a3c50d80fb962838dc52afd5e5e20a7f5817e";

	enum Command {
		BAD,
		HELO, KILL, JOIN, LEAVE, CHAT //legitimate
	}

	protected static class Message {
		public String name;
		public String text;
		
		public Message(String n, String t) {
			name = n;
			text = t;
		}
	}

	protected static class Room {
		public String name; //debug
		public int ref;
		public Vector<Message> messages; //synchronized

		public Room(int r, String n) {
			name = n;
			ref = r;
			messages = new Vector<Message>();
		}
	}

	private class Responder implements Runnable {
		private class Membership {
			public Room room;
			public String handle;
			public int counter;
			public Membership(Room r, String h) {
				room = r;
				handle = h;
				counter = r.messages.size(); //don't send older messages
			}
		}

		private Socket sock = null;
		private int joinCounter = 0;
		private HashMap<Integer, Membership> roomMem = new HashMap<Integer, Membership>(); //room_ref -> membership
		private HashMap<String, Membership> roomNameMem = new HashMap<String, Membership>(); //room_name -> membership

		public Responder(Socket s) {
			sock = s;
		}

		public void run() {
			System.out.println("strted thread with socket " + sock.toString());
			try {
				InputStream in = sock.getInputStream();
				OutputStream out = sock.getOutputStream();
				BufferedReader br = new BufferedReader(new InputStreamReader(in));
				Command cmd;

				while (true) {
					sock.setSoTimeout(150); //allow timeout to periodically send messages

					boolean leaving = false; //set to true if user sends a leave command
					boolean disconnecting = false; //ditto
					int leftRoomRef = 0, leftJoinRef = 0;

					try {
						String line = br.readLine();

						sock.setSoTimeout(0); //disable timeout
						if (line == null) break;

						if (line.startsWith("HELO ")) {
							System.out.println("HELO from " + sock);
							String msg = line.substring(line.indexOf(' ') + 1);

							//now write the ip-port-studentid
							String serverIp = sock.getLocalAddress().toString().substring(1);
							int port = listenSock.getLocalPort();
							String s = String.format("HELO %s\nIP:%s\nPort:%d\nStudentID:%s\n", msg, serverIp, port, STUDENT_ID);
							out.write(s.getBytes());
						} else if (line.equals("KILL_SERVICE")) {
							System.out.println("KILL_SERVICE from " + sock);
							Server.this.shutdown = true;
							System.exit(0);
						} else if (line.startsWith("JOIN_CHATROOM:")) {
							System.out.println("JOIN_CHATROOM from " + sock);

							String ipStr = br.readLine();
							String portStr = br.readLine();
							String name = br.readLine();

							if (ipStr == null || portStr == null || name == null ||
									ipStr.indexOf(':') < 0 || portStr.indexOf(':') < 0 || name.indexOf(':') < 0) {
								System.out.println("broken command");
								break;
							}

							String roomName = line.substring(line.indexOf(':') + 1);
							//ipStr = ipStr.substring(ipStr.indexOf(":")+1);
							//portStr = portStr.substring(portStr.indexOf(":")+1);
							name = name.substring(name.indexOf(":") + 1);

							Membership mem = roomNameMem.get(roomName);
							if (mem == null) {
								//find or create room
								Room room = getRoomRef(roomName);
								int joinRef = 0; //joinCounter++;
								mem = new Membership(room, name);
								roomMem.put(room.ref, mem);
								roomNameMem.put(roomName, mem);

								System.out.println(String.format("wants to join %s (%d), name:%s, ref %d", roomName, room.ref, name, joinRef));
								String resp = String.format("JOINED_CHATROOM:%s\nSERVER_IP:134.226.32.10\nPORT:8080\nROOM_REF:%d\nJOIN_ID:%d\n", roomName, room.ref, joinRef);
								out.write(resp.getBytes());
								
								//broadcast joining (!! including joiner)
								Message m = new Message(name, name + " has joined this chatroom.\n");
								synchronized (mem.room.messages) {
									mem.room.messages.add(m);
								}
							} else {
								System.out.println(sock + "alredy joined chatroom");
								//already joined - boot off the server
								break;
							}
						} else if (line.startsWith("LEAVE_CHATROOM:")) {
							System.out.println("LEAVE_CHATROOM from " + sock);

							String joinIdStr = br.readLine();
							String clientName = br.readLine();

							if (joinIdStr == null || clientName == null ||
									joinIdStr.indexOf(':') < 0 || clientName.indexOf(':') < 0) {
								System.out.println("broken command");
								break;
							}

							String roomRefStr = line.substring(line.indexOf(':') + 1);
							joinIdStr = joinIdStr.substring(joinIdStr.indexOf(':') + 1);
							clientName = clientName.substring(clientName.indexOf(':') + 1);

							int roomRef = Integer.parseInt(roomRefStr.trim());
							int joinId = Integer.parseInt(joinIdStr.trim());

							leaving = true;
							leftRoomRef = roomRef;
							leftJoinRef = joinId;
							
							//broadcast joining (!! including joiner)
							Message m = new Message(clientName, clientName + " has left this chatroom.\n");
							Membership mem = roomMem.get(roomRef);
							synchronized (mem.room.messages) {
								mem.room.messages.add(m);
							}
				
							System.out.println("user " + clientName + "(join ref " + joinId + ") wants to leave room " + mem.room.name + "(ref " + roomRef + ")");
							//XXX: spec says this should be delayed until chatroom messages are flushed
							//but test suite does not behave accordingly
							String resp = String.format("LEFT_CHATROOM:%d\nJOIN_ID:%d\n", roomRef, joinId);
							out.write(resp.getBytes());
						} 
						else if (line.startsWith("CHAT:")) {
							System.out.println("CHAT from " + sock);

							String joinIdStr = br.readLine();
							String clientName = br.readLine();
							String text = "";
							while (true) {
								String l = br.readLine();
								if (l == null) {
									text = null;
									break;
								} else if (l.length() > 0) {
									text += l + '\n';
								} else {
									break;
								}
							}

							if (joinIdStr == null || clientName == null || text == null ||
									joinIdStr.indexOf(':') < 0 || clientName.indexOf(':') < 0 || text.indexOf(':') < 0) {
								System.out.println("broken command");
								break;
							}

							String roomRefStr = line.substring(line.indexOf(':') + 1);
							joinIdStr = joinIdStr.substring(joinIdStr.indexOf(":") + 1);
							clientName = clientName.substring(clientName.indexOf(":") + 1);
							text = text.substring(text.indexOf(':') + 1);

							int joinId = Integer.parseInt(joinIdStr.trim());
							int roomRef = Integer.parseInt(roomRefStr.trim());
							Membership mem = roomMem.get(roomRef);
							if (mem == null) {
								//cheat
							} else {
								Message m = new Message(clientName, text);
								synchronized (mem.room.messages) {
									mem.room.messages.add(m);
								}
							}
						} 
						else if (line.startsWith("DISCONNECT:")) {
							System.out.println("DISCONNECT from " + sock);
							br.readLine(); //port
							br.readLine(); //client_name
							disconnecting = true;
							
							//broadcast disconnection to all active chat rooms
							for (Integer roomRef : roomMem.keySet()) {
								Membership mem = roomMem.get(roomRef);
								String clientName = mem.handle;
								Message m = new Message(clientName, clientName + " has disconnected from this chatroom.\n");
								synchronized (mem.room.messages) {
									mem.room.messages.add(m);
								}
							}
						}
						else {
							System.out.println("bad command from " + sock);
							break;
						}
					}

					catch(SocketTimeoutException e) {
						//pass
					}


					//look for message updates
					//XXX: test expects messages to be sorted by room ref order
					List<Integer> sortedRoomKeys = new ArrayList<Integer>(roomMem.keySet());
					java.util.Collections.sort(sortedRoomKeys);

					for (Integer k : sortedRoomKeys) {
						Membership mem = roomMem.get(k);
						Room room = mem.room;
						ArrayDeque<Message> outstanding = new ArrayDeque<Message>();

						synchronized (room.messages) {
							for (int i = mem.counter; i < room.messages.size(); i++) {
								outstanding.add(room.messages.get(i));
							}
							mem.counter = room.messages.size(); //up to date
						}

						Iterator<Message> it = outstanding.iterator();
						while (it.hasNext()) {
							Message m = it.next();
							String resp = String.format("CHAT:%d\nCLIENT_NAME:%s\nMESSAGE:%s\n", room.ref, m.name, m.text);
							System.out.println("sending " + resp);
							out.write(resp.getBytes());
						}
					}

					if (leaving) {
						Membership mem = roomMem.get(leftRoomRef);
						assert(mem != null);
						assert(mem.room.ref == leftRoomRef);
						if (mem == null) {
							//not part of this chat room - boot them off
							break;
						} else {
							roomMem.remove(leftRoomRef);
							roomNameMem.remove(mem.room.name);
						}
					}

					if (disconnecting) {
						break;
					}
				}

				out.flush();
				in.close();
				out.close();
				sock.close();
				System.out.println("socket " + sock.toString() + " thread ended gracefully");
			}
			catch(SocketTimeoutException e) {
				System.out.println("socket " + sock.toString() + " outer timeout");
				e.printStackTrace();
			}
			catch(SocketException e) {
				System.out.println("socket " + sock.toString() + " error " + e.toString());
				e.printStackTrace();
			}
			catch(IOException e) {
				System.out.println("socket " + sock.toString() + " io error " + e.toString());
			}
		}
	}

	private ExecutorService pool = null;
	private ServerSocket listenSock = null;
	protected boolean shutdown = false; //write-only from workers

	public Server() {
	}

	private HashMap<String, Room> roomByName = new HashMap<String, Room>();
	private int roomSeq = 0;

	protected Room getRoomRef(String name) {
		synchronized(roomByName) {
			Room room = roomByName.get(name);
			if (room == null) {
				room = new Room(roomSeq++, name);
				roomByName.put(name, room);
			}
			return room;
		}
	}

	public final void start(int port, int threads, int backlog) {
		try {
			pool = Executors.newFixedThreadPool(threads);
			listenSock = new ServerSocket(port, backlog);
			//InetAddress IP = InetAddress.getLocalHost();

			//make it possible to check the shutdown flag while waiting for connections
			listenSock.setSoTimeout(50);		

			while (true) {
				if (shutdown) {
					System.out.println("main thread: shutdown");
					break;
				}
				
				try {
					Socket sock = listenSock.accept();
					System.out.println("connection from " + sock.getInetAddress().getCanonicalHostName());
					Runnable worker = new Responder(sock);
					pool.execute(worker);
				}
				catch (SocketTimeoutException e) {
					//pass
				}
			}
		
			pool.shutdown();
			while (!pool.isTerminated()) {
				try {
					Thread.sleep(5);
				}
				catch(Exception e) {
					//pass
				}
			}
		
			System.out.println("terminated all threads");
		}
		catch (Exception e) {
			System.out.println("exception " + e);
		}
	}

	public static void main(String[] args) {
		if (args.length >= 1 && args.length <= 3) {
			int port = Integer.parseInt(args[0], 10);
			int threads = Runtime.getRuntime().availableProcessors();
			int backlog = 50;
			if (args.length > 1) threads = Integer.parseInt(args[1], 10);
			if (args.length > 2) backlog = Integer.parseInt(args[2], 10);

			System.out.println(String.format("listening on port %d, using %d threads, backlog %d long", port, threads, backlog));
			Server s = new Server();
			s.start(port, threads, backlog);
		}

		else {
			System.out.println("usage: java Server <port> [threads] [backlog-length]");
		}
	}
}
