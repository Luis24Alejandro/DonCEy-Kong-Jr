// Ruta: Server/Server.java
package Server;

import Server.entities.*;
import Server.factory.*;

import java.io.IOException;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public final class Server {
    // --- Clase interna para eventos de input ---
    static final class InputEvent {
        final int playerId, seq, dx, dy;
        InputEvent(int playerId, int seq, int dx, int dy) {
            this.playerId = playerId; this.seq = seq; this.dx = dx; this.dy = dy;
        }
    }

    // --- Singleton ---
    private static volatile Server instance;
    private Server() {}
    public static Server getInstance() {
        if (instance == null) {
            synchronized (Server.class) { if (instance == null) instance = new Server(); }
        }
        return instance;
    }
    // --- /Singleton ---

    private ServerSocket serverSocket;
    private final int port = 5000;

    // Pool y clientes
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private final CopyOnWriteArrayList<ClientHandler> clients = new CopyOnWriteArrayList<>();

    // Estado del juego
    private final AtomicInteger nextId = new AtomicInteger(1);
    private final AtomicInteger tickSeq = new AtomicInteger(0); // secuencia de STATE
    final ConcurrentHashMap<Integer, Player> players = new ConcurrentHashMap<>();
    final ConcurrentHashMap<ClientHandler, Integer> byClient = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<InputEvent> inputQueue = new ConcurrentLinkedQueue<>();

    // >>> Nuevo: separar audiencias
    private final Set<ClientHandler> playerClients =
            Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final ConcurrentHashMap<Integer, CopyOnWriteArrayList<ClientHandler>> spectatorsByPlayer =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, CopyOnWriteArrayList<ClientHandler>> waitingSpectatorsByPlayer =
            new ConcurrentHashMap<>();

    // Admin: Abstract Factory + entidades
    private final GameElementFactory factory = new DefaultFactory();
    private final CopyOnWriteArrayList<Enemy> enemies = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Fruit> fruits  = new CopyOnWriteArrayList<>();

    // Constantes de mapa simples (ajústalas a tu tablero real)
    private static final int MIN_Y = 0;
    private static final int MAX_Y = 10;
    private static final int MIN_X = 0;
    private static final int MAX_X = 10;

    // Loop de simulación (Observer)
    private final ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor();

    public void start() throws IOException {
        if (serverSocket != null && !serverSocket.isClosed()) return;

        serverSocket = new ServerSocket();
        serverSocket.bind(new InetSocketAddress("127.0.0.1", port));
        System.out.println("[JAVA] Servidor escuchando en puerto " + port + " ...");

        // Tick del juego (10 Hz = 100 ms para pruebas)
        ticker.scheduleAtFixedRate(this::tick, 100, 100, TimeUnit.MILLISECONDS);

        // Hilo admin por consola
        new Thread(this::adminLoop, "AdminConsole").start();

        while (!serverSocket.isClosed()) {
            try {
                Socket socket = serverSocket.accept();
                socket.setTcpNoDelay(true);
                System.out.println("[JAVA] Cliente conectado: " + socket.getRemoteSocketAddress());

                ClientHandler handler = new ClientHandler(socket, this);
                clients.add(handler);
                pool.submit(handler);
            } catch (SocketException se) {
                System.out.println("[JAVA] Aceptación detenida: " + se.getMessage());
                break;
            }
        }
    }

    /** Loop de simulación: procesa inputs, avanza enemigos y notifica a jugadores/espectadores. */
    private void tick() {
        // 1) Consumir inputs
        InputEvent ev;
        while ((ev = inputQueue.poll()) != null) {
            Player p = players.get(ev.playerId);
            if (p == null) continue;
            int nx = p.x + ev.dx;
            int ny = p.y + ev.dy;
            if (nx < MIN_X) nx = MIN_X; if (nx > MAX_X) nx = MAX_X;
            if (ny < MIN_Y) ny = MIN_Y; if (ny > MAX_Y) ny = MAX_Y;
            p.x = nx; p.y = ny;
            p.lastAckSeq = Math.max(p.lastAckSeq, ev.seq);
        }

        // 2) Avanzar enemigos (abstract factory)
        for (Enemy e : enemies) e.tick(MIN_Y, MAX_Y);

        // 3) Difundir snapshot
        int s = tickSeq.incrementAndGet();
        StringBuilder state = new StringBuilder();
        state.append("STATE ").append(s).append(' ');
        for (Map.Entry<Integer, Player> e : players.entrySet()) {
            Player p = e.getValue();
            state.append(p.id).append(' ').append(p.x).append(' ').append(p.y).append(' ')
                 .append(p.lastAckSeq).append(';');
        }
        if (!enemies.isEmpty()) {
            state.append(" | ENEMIES ");
            for (Enemy enemy : enemies) {
                state.append(enemy.getType()).append(' ')
                     .append(enemy.getX()).append(' ')
                     .append(enemy.getY()).append(';');
            }
        }
        if (!fruits.isEmpty()) {
            state.append(" | FRUITS ");
            for (Fruit f : fruits) {
                state.append(f.getX()).append(' ')
                     .append(f.getY()).append(' ')
                     .append(f.getPoints()).append(';');
            }
        }
        state.append('\n');

        // >>> Cambiado: enviar STATE solo a jugadores
        for (ClientHandler ch : playerClients) ch.sendLine(state.toString());

        // A espectadores (observer filtrado por jugador)
        players.forEach((pid, p) -> {
            var ls = spectatorsByPlayer.get(pid);
            if (ls != null && !ls.isEmpty()) {
                String obsLine = "OBS " + pid + " " + s + " "
                        + p.id + " " + p.x + " " + p.y + " " + p.lastAckSeq + ";\n";
                for (ClientHandler ch : ls) ch.sendLine(obsLine);
            }
        });
    }

    /* ======================= API que llama ClientHandler ======================= */

    // Máximo dos jugadores activos
    void onJoin(ClientHandler c, String name) {
        long activePlayers = players.size();
        if (activePlayers >= 2) {
            c.sendLine("ERR MAX_PLAYERS\n");
            return;
        }
        int id = nextId.getAndIncrement();
        Player p = new Player(id, name);
        players.put(id, p);
        byClient.put(c, id);
        playerClients.add(c); // ahora este handler recibe STATE

        c.sendLine("ASSIGN " + id + "\n");
        System.out.println("[JAVA] JOIN -> id=" + id + " name=" + name);

        // Activar espectadores en espera (si los hay)
        var waiting = waitingSpectatorsByPlayer.remove(id);
        if (waiting != null && !waiting.isEmpty()) {
            var list = spectatorsByPlayer.computeIfAbsent(id, k -> new CopyOnWriteArrayList<>());
            for (ClientHandler w : waiting) {
                list.add(w);
                w.sendLine("OK SPECTATING " + id + "\n");
            }
            System.out.println("[JAVA] Activados " + waiting.size() + " espectadores para id=" + id);
        }
    }

    void onInput(ClientHandler c, int seq, int dx, int dy) {
        Integer id = byClient.get(c);
        if (id == null) { c.sendLine("ERR NOT_JOINED\n"); return; }
        if (Math.abs(dx) > 1 || Math.abs(dy) > 1) { c.sendLine("ERR STEP_TOO_BIG\n"); return; }
        inputQueue.offer(new InputEvent(id, seq, dx, dy));
    }

    // Máximo dos espectadores por jugador + modo "waiting"
    void onSpectate(ClientHandler c, int playerId) {
        if (players.containsKey(playerId)) {
            var list = spectatorsByPlayer.computeIfAbsent(playerId, k -> new CopyOnWriteArrayList<>());
            if (list.size() >= 2) { c.sendLine("ERR MAX_SPECTATORS\n"); return; }
            list.add(c);
            c.sendLine("OK SPECTATING " + playerId + "\n");
            System.out.println("[JAVA] SPECTATE -> viewer=" + c + " playerId=" + playerId + " total=" + list.size());
            return;
        }
        var waitList = waitingSpectatorsByPlayer.computeIfAbsent(playerId, k -> new CopyOnWriteArrayList<>());
        waitList.add(c);
        c.sendLine("OK WAITING " + playerId + "\n");
        System.out.println("[JAVA] SPECTATE (waiting) -> viewer=" + c + " playerId=" + playerId + " totalWaiting=" + waitList.size());
    }

    void onQuit(ClientHandler c) {
        Integer id = byClient.remove(c);
        if (id != null) {
            players.remove(id);
            playerClients.remove(c);

            // Notificar fin a sus espectadores activos
            var ls = spectatorsByPlayer.remove(id);
            if (ls != null) {
                for (ClientHandler sp : ls) sp.sendLine("OBS_END " + id + "\n");
            }
            waitingSpectatorsByPlayer.remove(id);
            System.out.println("[JAVA] QUIT -> id=" + id);
        } else {
            // Era espectador: limpiar de todas las listas
            spectatorsByPlayer.forEach((pid, ls) -> ls.remove(c));
            waitingSpectatorsByPlayer.forEach((pid, ls) -> ls.remove(c));
        }
    }

    /* ================================ Admin =================================== */

    private void adminLoop() {
        try (Scanner sc = new Scanner(System.in)) {
            while (true) {
                String line = sc.nextLine().trim();
                if (line.equalsIgnoreCase("exit")) { stop(); break; }
                handleAdminCommand(line);
            }
        } catch (Exception ignored) {}
    }

    // ADMIN CROCODILE <RED|BLUE> <liana> [y]
    // ADMIN FRUIT CREATE <liana> <y> <points>
    // ADMIN FRUIT DELETE <liana> <y>
    private void handleAdminCommand(String line) {
        try {
            String[] t = line.split("\\s+");
            if (t.length >= 4 && "ADMIN".equalsIgnoreCase(t[0]) && "CROCODILE".equalsIgnoreCase(t[1])) {
                String type = t[2];
                int liana = Integer.parseInt(t[3]);
                int y = (t.length > 4) ? Integer.parseInt(t[4]) : MIN_Y;
                enemies.add(factory.createCrocodile(type, liana, y));
                System.out.println("[ADMIN] CROCODILE " + type + " @" + liana + "," + y);
            } else if (t.length >= 6 && "ADMIN".equalsIgnoreCase(t[0]) && "FRUIT".equalsIgnoreCase(t[1]) && "CREATE".equalsIgnoreCase(t[2])) {
                int l = Integer.parseInt(t[3]), y = Integer.parseInt(t[4]), pts = Integer.parseInt(t[5]);
                fruits.add(factory.createFruit(l, y, pts));
                System.out.println("[ADMIN] FRUIT +" + l + "," + y + " pts=" + pts);
            } else if (t.length >= 5 && "ADMIN".equalsIgnoreCase(t[0]) && "FRUIT".equalsIgnoreCase(t[1]) && "DELETE".equalsIgnoreCase(t[2])) {
                int l = Integer.parseInt(t[3]), y = Integer.parseInt(t[4]);
                fruits.removeIf(f -> f.getX() == l && f.getY() == y);
                System.out.println("[ADMIN] FRUIT -" + l + "," + y);
            } else {
                System.out.println("[ADMIN] Comando inválido");
            }
        } catch (Exception e) {
            System.out.println("[ADMIN] Error: " + e.getMessage());
        }
    }

    /* ================================ Utils =================================== */

    public void broadcast(String line) {
        for (ClientHandler ch : clients) ch.sendLine(line);
    }

    void removeClient(ClientHandler c) {
        clients.remove(c);
        onQuit(c);
        System.out.println("[JAVA] Cliente removido. Conectados: " + clients.size());
    }

    public void stop() {
        try {
            ticker.shutdownNow();
            for (ClientHandler c : clients) c.close();
            clients.clear();
            pool.shutdownNow();
            if (serverSocket != null && !serverSocket.isClosed()) serverSocket.close();
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
