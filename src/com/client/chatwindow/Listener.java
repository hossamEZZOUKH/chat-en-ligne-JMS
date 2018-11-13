package com.client.chatwindow;

import com.client.login.LoginController;
import com.exception.DuplicateUsernameException;
import com.messages.Message;
import com.messages.MessageType;
import com.messages.Status;
import com.messages.User;

import org.apache.activemq.ActiveMQConnectionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.UUID;

import javax.jms.Connection;
import javax.jms.DeliveryMode;
import javax.jms.JMSException;
import javax.jms.MessageConsumer;
import javax.jms.MessageProducer;
import javax.jms.ObjectMessage;
import javax.jms.Session;
import javax.jms.Topic;

import static com.messages.MessageType.CONNECTED;

public class Listener implements Runnable {

	private static final String HASCONNECTED = "has connected";

	private static String picture;
	// private Socket socket;
	public String hostname;
	public int port;
	public static String username;
	public ChatController controller;
	private static ObjectOutputStream oos;
	private InputStream is;
	private ObjectInputStream input;
	private OutputStream outputStream;
	static Logger logger = LoggerFactory.getLogger(Listener.class);

	/* valeurs qui etaient dans le serveur */
	private static  HashMap<String, User> names = new HashMap<>();
	private String name;
	private static User user;
	private static ArrayList<User> users = new ArrayList<>();

	/* truc de connexion ... */
	private static javax.jms.Message messageJms;
	private static ActiveMQConnectionFactory connectionFactory;
	private static Connection connection;
	private static Session session;
	private static MessageProducer producer;
	private static Topic destination;
	private static MessageConsumer consumer;
	private static ObjectMessage objectMessage;

	public Listener(String hostname, int port, String username, String picture, ChatController controller) {
		this.hostname = hostname;
		this.port = port;
		Listener.username = username;
		Listener.picture = picture;
		this.controller = controller;
	}

	/*
	 * added so that to call them from ChatController in case of
	 * pointerNullException
	 */
	public static ArrayList<User> getUsers() {
		return users;
	}

	public static void setUsers(ArrayList<User> users) {
		Listener.users = users;
	}

	public void run() {
		try {
			System.setProperty("org.apache.activemq.SERIALIZABLE_PACKAGES", "*");
			connectionFactory = new ActiveMQConnectionFactory("tcp://localhost:61616");
			connection = connectionFactory.createConnection();
			String clientId = UUID.randomUUID().toString();
			connection.setClientID(clientId);
			connection.start();
			session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
			destination = session.createTopic("DemoTopic");

			producer = session.createProducer(destination);
			producer.setDeliveryMode(DeliveryMode.NON_PERSISTENT);
			consumer = session.createDurableSubscriber(destination, "client");
			objectMessage = session.createObjectMessage();
			LoginController.getInstance().showScene();
		} catch (IOException | JMSException e) {
			LoginController.getInstance().showErrorDialog("Could not connect to server");
			logger.error("Could not Connect");
		}

		try {
			connect();
			logger.info("JMS READY!");
			messageJms = consumer.receive();
			Message firstMessage = (Message) ((ObjectMessage) messageJms).getObject();
			checkDuplicateUsername(firstMessage);
			sendNotification(firstMessage);
		
			
			
			
				while (true) {
					messageJms = consumer.receive();
					Message message = (Message) ((ObjectMessage) messageJms).getObject();

					if (message != null) {
						logger.debug("Message recieved:" + message.getMsg() + " MessageType:" + message.getType()
								+ "Name:" + message.getName());
						switch (message.getType()) {
					
						case USER:
							System.out.println("(" + name + ") message gotten from : " + message.getName());
							System.out.println("msg : " + message.getMsg());
							controller.addToChat(message);
							break;
						case VOICE:
							controller.addToChat(message);
							break;
						case NOTIFICATION:
							controller.newUserNotification(message);
							break;
						case SERVER:
							controller.addAsServer(message);
							break;
						case CONNECTED:
							
							controller.setUserList(message);
							break;
						case DISCONNECTED:
							controller.setUserList(message);
							break;
						case STATUS:
							
							controller.setUserList(message);
							break;
						}
					}
				}

		} catch (IOException | JMSException | DuplicateUsernameException e) {
			e.printStackTrace();
			controller.logoutScene();
		} finally {
			closeConnections();
		}
	}

	/* added from server */
	private synchronized void checkDuplicateUsername(Message firstMessage) throws DuplicateUsernameException {
		logger.info(firstMessage.getName() + " is trying to connect");
		if (!names.containsKey(firstMessage.getName())) {
			this.name = firstMessage.getName();
			user = new User();
			user.setName(firstMessage.getName());
			user.setStatus(Status.ONLINE);
			user.setPicture(firstMessage.getPicture());

			users.add(user);
			names.put(name, user);

			logger.info(name + " has been added to the list");
		} else {
			logger.error(firstMessage.getName() + " is already connected");
			throw new DuplicateUsernameException(firstMessage.getName() + " is already connected");
		}
	}

	/* added from server */
	private Message sendNotification(Message firstMessage) throws IOException, JMSException {
		Message msg = new Message();
		msg.setMsg("has joined the chat.");
		msg.setType(MessageType.NOTIFICATION);
		msg.setName(firstMessage.getName());
		msg.setPicture(firstMessage.getPicture());
		write(msg);
		return msg;
	}

	/* added from server */
	private static void write(Message msg) throws IOException, JMSException {
		msg.setUserlist(names);
		msg.setUsers(users);
		msg.setOnlineCount(names.size());
		ObjectMessage objectMessage = session.createObjectMessage();
		objectMessage.setObject(msg);
		logger.info(objectMessage.toString() + " " + msg.getName() + " " + msg.getUserlist().toString());
		producer.send(objectMessage);
	}

	/* added from server */
	private synchronized void closeConnections() {
		logger.debug("closeConnections() method Enter");
		logger.info("HashMap names:" + names.size() + " usersList size:" + users.size());
		if (name != null) {
			names.remove(name);
			logger.info("User: " + name + " has been removed!");
		}
		if (user != null) {
			users.remove(user);
			logger.info("User object: " + user + " has been removed!");
		}
		try {
			removeFromList();
		} catch (Exception e) {
			e.printStackTrace();
		}
		logger.info("HashMap names:" + names.size() + " usersList size:" + users.size());
		logger.debug("closeConnections() method Exit");
	}

	/***** ajouter de Server *****/
	private Message removeFromList() throws IOException, JMSException {
		logger.debug("removeFromList() method Enter");
		Message msg = new Message();
		msg.setMsg("has left the chat.");
		msg.setType(MessageType.DISCONNECTED);
		msg.setName("SERVER");
		msg.setUserlist(names);
		write(msg);
		logger.debug("removeFromList() method Exit");
		return msg;
	}

	/*
	 * This method is used for sending a normal Message
	 * 
	 * @param msg - The message which the user generates
	 */
	public static void send(String msg) throws IOException, JMSException {
		Message createMessage = new Message();
		createMessage.setName(username);
		createMessage.setType(MessageType.USER);
		createMessage.setStatus(Status.AWAY);
		createMessage.setMsg(msg);
		createMessage.setPicture(picture);
		write(createMessage);
	}

	/*
	 * This method is used for sending a voice Message
	 * 
	 * @param msg - The message which the user generates
	 */
	public static void sendVoiceMessage(byte[] audio) throws IOException, JMSException {
		Message createMessage = new Message();
		createMessage.setName(username);
		createMessage.setType(MessageType.VOICE);
		createMessage.setStatus(Status.AWAY);
		createMessage.setVoiceMsg(audio);
		createMessage.setPicture(picture);
		write(createMessage);
	}

	/*
	 * This method is used for sending a normal Message
	 * 
	 * @param msg - The message which the user generates
	 */
	public static void sendStatusUpdate(Status status) throws IOException, JMSException {
		Message createMessage = new Message();
		createMessage.setName(username);
		createMessage.setType(MessageType.STATUS);
		createMessage.setStatus(status);
		createMessage.setPicture(picture);
		ObjectMessage objectMessage = session.createObjectMessage();
		objectMessage.setObject(createMessage);
		producer.send(objectMessage);
	}

	/* This method is used to send a connecting message */
	public static void connect() throws IOException, JMSException {
		Message createMessage = new Message();
		createMessage.setName(username);
		createMessage.setType(CONNECTED);
		createMessage.setMsg(HASCONNECTED);
		createMessage.setPicture(picture);
		ObjectMessage objectMessage = session.createObjectMessage();
		objectMessage.setObject(createMessage);
		producer.send(objectMessage);
	}
	 
	  
	 
}
