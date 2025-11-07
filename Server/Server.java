// Ruta: Server/Server.java
package Server;

import java.io.IOException;
import java.net.*;
import java.util.List;
import java.util.concurrent.*;

public final class Server {
    // --- Singleton ---
    private static volatile Server instance;
    private Server() {}
    public static Server getInstance() {
        if (instance == null) {
            synchronized (Server.class) {
                if (instance == null) instance = new Server();
            }
        }
        return instance;
    }
    // --- /Singleton ---

    private ServerSocket serverSocket;
    private final int port = 5000;

    // Pool de hilos para clientes
    private final ExecutorService pool = Executors.newCachedThreadPool();
    // Lista segura para iterar y difundir mensajes
    private final CopyOnWriteArrayList<ClientHandler> clients = new CopyOnWriteArrayList<>();

    public void start() throws IOException {
        if (serverSocket != null && !serverSocket.isClosed()) return;

        serverSocket = new ServerSocket();
        serverSocket.bind(new InetSocketAddress("127.0.0.1", port)); // bind explícito a localhost
        System.out.println("[JAVA] Servidor escuchando en puerto " + port + " ...");

        // Acepta clientes para siempre
        while (!serverSocket.isClosed()) {
            try {
                Socket socket = serverSocket.accept();
                socket.setTcpNoDelay(true);
                System.out.println("[JAVA] Cliente conectado: " + socket.getRemoteSocketAddress());

                ClientHandler handler = new ClientHandler(socket, this);
                clients.add(handler);
                pool.submit(handler);
            } catch (SocketException se) {
                // Se cerró el serverSocket desde stop()
                System.out.println("[JAVA] Aceptación detenida: " + se.getMessage());
                break;
            }
        }
    }

    /** Enviar mensaje a todos los clientes (útil para estado global del juego). */
    public void broadcast(String line) {
        for (ClientHandler c : clients) {
            c.sendLine(line);
        }
    }

    /** Enviar mensaje a un cliente en particular (si quieres direccionamiento por id). */
    public void sendTo(ClientHandler target, String line) {
        target.sendLine(line);
    }

    /** Remueve cliente (lo llama el handler al desconectarse). */
    void removeClient(ClientHandler c) {
        clients.remove(c);
        System.out.println("[JAVA] Cliente removido. Conectados: " + clients.size());
    }

    /** Detener servidor y cerrar todo. */
    public void stop() {
        try {
            for (ClientHandler c : clients) c.close();
            clients.clear();
            pool.shutdownNow();

            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
            System.out.println("[JAVA] Servidor detenido.");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void main(String[] args) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("[JAVA] Shutdown hook → cerrando servidor...");
            Server.getInstance().stop();
        }));
        try {
            Server.getInstance().start();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}

