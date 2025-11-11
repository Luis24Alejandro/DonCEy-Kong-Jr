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
    /* ========= Eventos de input ========= */
    static final class InputEvent {
        final int playerId, seq, dx, dy;
        InputEvent(int playerId, int seq, int dx, int dy) {
            this.playerId = playerId; this.seq = seq; this.dx = dx; this.dy = dy;
        }
    }

    /* ========= Singleton ========= */
    private static volatile Server instance;
    private Server() {}
    public static Server getInstance() {
        if (instance == null) {
            synchronized (Server.class) { if (instance == null) instance = new Server(); }
        }
        return instance;
    }

    /* ========= Sockets / pools ========= */
    private ServerSocket serverSocket;
    private final int port = 5000;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private final CopyOnWriteArrayList<ClientHandler> clients = new CopyOnWriteArrayList<>();

    /* ========= Estado básico ========= */
    private final AtomicInteger nextId = new AtomicInteger(1);
    private final AtomicInteger tickSeq = new AtomicInteger(0);
    final ConcurrentHashMap<Integer, Player> players = new ConcurrentHashMap<>();
    final ConcurrentHashMap<ClientHandler, Integer> byClient = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<InputEvent> inputQueue = new ConcurrentLinkedQueue<>();

    /* ========= Jugadores vs espectadores ========= */
    private final Set<ClientHandler> playerClients =
            Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final ConcurrentHashMap<Integer, CopyOnWriteArrayList<ClientHandler>> spectatorsByPlayer =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, CopyOnWriteArrayList<ClientHandler>> waitingSpectatorsByPlayer =
            new ConcurrentHashMap<>();

    /* ========= Plantillas del Admin (Abstract Factory) ========= */
    private final GameElementFactory factory = new DefaultFactory();
    private final CopyOnWriteArrayList<Enemy> templateEnemies = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Fruit> templateFruits  = new CopyOnWriteArrayList<>();

    /* ========= Sesiones por jugador ========= */
    private final ConcurrentHashMap<Integer, GameSession> sessions = new ConcurrentHashMap<>();

    /* ========= Mapa (ajústalo a tu tablero) ========= */
    private static final int MIN_Y = 0, MAX_Y = 10;
    private static final int MIN_X = 0, MAX_X = 10;

    /* ========= Loop ========= */
    private final ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor();

    public void start() throws IOException {
        if (serverSocket != null && !serverSocket.isClosed()) return;

        serverSocket = new ServerSocket();
        serverSocket.bind(new InetSocketAddress("127.0.0.1", port));
        System.out.println("[JAVA] Servidor escuchando en puerto " + port + " ...");

        ticker.scheduleAtFixedRate(this::tick, 100, 100, TimeUnit.MILLISECONDS);
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

    /* ========= TICK: procesa inputs, simula enemigos y notifica ========= */
    private void tick() {
        // 1) inputs → posiciones (con límites) + ack
        InputEvent ev;
        while ((ev = inputQueue.poll()) != null) {
            Player p = players.get(ev.playerId);
            if (p == null) continue;
            int nx = p.x + ev.dx, ny = p.y + ev.dy;
            if (nx < MIN_X) nx = MIN_X; if (nx > MAX_X) nx = MAX_X;
            if (ny < MIN_Y) ny = MIN_Y; if (ny > MAX_Y) ny = MAX_Y;
            p.x = nx; p.y = ny; p.lastAckSeq = Math.max(p.lastAckSeq, ev.seq);
        }

        // 2) simular enemigos por sesión y chequear eventos de juego
        for (var e : sessions.entrySet()) {
            int pid = e.getKey();
            GameSession s = e.getValue();
            Player p = players.get(pid);
            if (p == null) continue;

            // avanzar enemigos con “velocidad” de la ronda de ese jugador
            for (int step = 0; step < Math.max(1, s.enemySpeedSteps); step++) {
                for (Enemy enemy : s.enemies) enemy.tick(MIN_Y, MAX_Y);
            }

            // colisiones con enemigos → LOSE (juego termina para ese jugador)
            boolean hit = s.enemies.stream().anyMatch(en -> en.getX() == p.x && en.getY() == p.y);
            if (hit) {
                sendToPlayerAndSpectators(pid, "LOSE " + pid + " " + p.score + "\n");
                // cerrar sesión de ese jugador
                endPlayerSession(pid);
                continue;
            }

            // recoger frutas → +pts y borrar fruta
            Iterator<Fruit> it = s.fruits.iterator();
            while (it.hasNext()) {
                Fruit f = it.next();
                if (f.getX() == p.x && f.getY() == p.y) {
                    p.score += f.getPoints();
                    it.remove();
                    sendToPlayerAndSpectators(pid, "SCORE " + pid + " " + p.score + "\n");
                }
            }

            // llegar a la meta → subir ronda, subir velocidad, sumar bonus y respawn
            if (p.x == s.goalX && p.y == s.goalY) {
                p.score += 100; // bonus de ronda
                p.round += 1;
                s.enemySpeedSteps += 1; // más rápido cada ronda
                p.x = s.spawnX; p.y = s.spawnY;
                sendToPlayerAndSpectators(pid, "SCORE " + pid + " " + p.score + "\n");
                sendToPlayerAndSpectators(pid, "ROUND " + pid + " " + p.round + " " + s.enemySpeedSteps + "\n");
            }
        }

        // 3) STATE (pos, ack, score, round) a jugadores; OBS a espectadores
        int t = tickSeq.incrementAndGet();

        // STATE a jugadores
        StringBuilder state = new StringBuilder();
        state.append("STATE ").append(t).append(' ');
        for (Map.Entry<Integer, Player> e : players.entrySet()) {
            Player p = e.getValue();
            state.append(p.id).append(' ').append(p.x).append(' ').append(p.y).append(' ')
                 .append(p.lastAckSeq).append(' ').append(p.score).append(' ').append(p.round).append(';');
        }
        state.append('\n');
        for (ClientHandler ch : playerClients) ch.sendLine(state.toString());

        // OBS filtrado por jugador
        players.forEach((pid, p) -> {
            var ls = spectatorsByPlayer.get(pid);
            if (ls != null && !ls.isEmpty()) {
                String obs = "OBS " + pid + " " + t + " "
                           + p.id + " " + p.x + " " + p.y + " "
                           + p.lastAckSeq + " " + p.score + " " + p.round + ";\n";
                for (ClientHandler ch : ls) ch.sendLine(obs);
            }
        });
    }

    /* ========= API para ClientHandler ========= */
    void onJoin(ClientHandler c, String name) {
        if (players.size() >= 2) { c.sendLine("ERR MAX_PLAYERS\n"); return; }

        int id = nextId.getAndIncrement();
        Player p = new Player(id, name);
        players.put(id, p);
        byClient.put(c, id);
        playerClients.add(c);

        // crear sesión del jugador con clones de la plantilla admin
        GameSession s = new GameSession(id);
        s.loadFromTemplates(templateEnemies, templateFruits);
        sessions.put(id, s);

        c.sendLine("ASSIGN " + id + "\n");
        // si había espectadores en espera: activarlos
        var waiting = waitingSpectatorsByPlayer.remove(id);
        if (waiting != null && !waiting.isEmpty()) {
            var list = spectatorsByPlayer.computeIfAbsent(id, k -> new CopyOnWriteArrayList<>());
            for (ClientHandler w : waiting) { list.add(w); w.sendLine("OK SPECTATING " + id + "\n"); }
        }
        System.out.println("[JAVA] JOIN -> id=" + id + " name=" + name);
    }

    void onInput(ClientHandler c, int seq, int dx, int dy) {
        Integer id = byClient.get(c);
        if (id == null) { c.sendLine("ERR NOT_JOINED\n"); return; }
        if (Math.abs(dx) > 1 || Math.abs(dy) > 1) { c.sendLine("ERR STEP_TOO_BIG\n"); return; }
        inputQueue.offer(new InputEvent(id, seq, dx, dy));
    }

    void onSpectate(ClientHandler c, int playerId) {
        if (players.containsKey(playerId)) {
            var list = spectatorsByPlayer.computeIfAbsent(playerId, k -> new CopyOnWriteArrayList<>());
            if (list.size() >= 2) { c.sendLine("ERR MAX_SPECTATORS\n"); return; }
            list.add(c);
            c.sendLine("OK SPECTATING " + playerId + "\n");
            return;
        }
        var waitList = waitingSpectatorsByPlayer.computeIfAbsent(playerId, k -> new CopyOnWriteArrayList<>());
        waitList.add(c);
        c.sendLine("OK WAITING " + playerId + "\n");
    }

    void onQuit(ClientHandler c) {
        Integer id = byClient.remove(c);
        if (id != null) {
            endPlayerSession(id);
        } else {
            spectatorsByPlayer.forEach((pid, ls) -> ls.remove(c));
            waitingSpectatorsByPlayer.forEach((pid, ls) -> ls.remove(c));
        }
    }

    /* ========= Utilidades ========= */
    private void endPlayerSession(int playerId) {
        // avisar a espectadores y limpiar
        var ls = spectatorsByPlayer.remove(playerId);
        if (ls != null) for (ClientHandler sp : ls) sp.sendLine("OBS_END " + playerId + "\n");
        waitingSpectatorsByPlayer.remove(playerId);

        // cerrar handler del jugador si sigue conectado
        ClientHandler toClose = null;
        for (Map.Entry<ClientHandler,Integer> e : byClient.entrySet()) {
            if (e.getValue() == playerId) { toClose = e.getKey(); break; }
        }
        if (toClose != null) {
            byClient.remove(toClose);
            playerClients.remove(toClose);
            toClose.sendLine("BYE\n");
            toClose.close();
            clients.remove(toClose);
        }
        players.remove(playerId);
        sessions.remove(playerId);
        System.out.println("[JAVA] Fin de sesión -> id=" + playerId);
    }

    private void sendToPlayerAndSpectators(int playerId, String line) {
        // a jugador:
        for (Map.Entry<ClientHandler,Integer> e : byClient.entrySet()) {
            if (e.getValue() == playerId) e.getKey().sendLine(line);
        }
        // a sus espectadores:
        var ls = spectatorsByPlayer.get(playerId);
        if (ls != null) for (ClientHandler ch : ls) ch.sendLine(line);
    }

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
        } catch (IOException e) { e.printStackTrace(); }
    }

    /* ========= Consola Admin (Abstract Factory) ========= */
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
                String type = t[2]; int liana = Integer.parseInt(t[3]);
                int y = (t.length > 4) ? Integer.parseInt(t[4]) : MIN_Y;
                templateEnemies.add(factory.createCrocodile(type, liana, y));
                System.out.println("[ADMIN] CROCODILE " + type + " @" + liana + "," + y + " (plantilla)");
            } else if (t.length >= 6 && "ADMIN".equalsIgnoreCase(t[0]) && "FRUIT".equalsIgnoreCase(t[1]) && "CREATE".equalsIgnoreCase(t[2])) {
                int l = Integer.parseInt(t[3]), y = Integer.parseInt(t[4]), pts = Integer.parseInt(t[5]);
                templateFruits.add(factory.createFruit(l, y, pts));
                System.out.println("[ADMIN] FRUIT +" + l + "," + y + " pts=" + pts + " (plantilla)");
            } else if (t.length >= 5 && "ADMIN".equalsIgnoreCase(t[0]) && "FRUIT".equalsIgnoreCase(t[1]) && "DELETE".equalsIgnoreCase(t[2])) {
                int l = Integer.parseInt(t[3]), y = Integer.parseInt(t[4]);
                templateFruits.removeIf(f -> f.getX()==l && f.getY()==y);
                System.out.println("[ADMIN] FRUIT -" + l + "," + y + " (plantilla)");
            } else {
                System.out.println("[ADMIN] Comando inválido");
            }
        } catch (Exception e) {
            System.out.println("[ADMIN] Error: " + e.getMessage());
        }
    }

    public static void main(String[] args) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("[JAVA] Shutdown hook → cerrando servidor...");
            Server.getInstance().stop();
        }));
        try { Server.getInstance().start(); } catch (IOException e) { e.printStackTrace(); }
    }
}

