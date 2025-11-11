// Ruta: Server/GameSession.java
package Server;

import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import Server.entities.Enemy;
import Server.entities.Fruit;
import Server.entities.RedCroc;
import Server.entities.BlueCroc;
import Server.entities.SimpleFruit;

public class GameSession {
    public final int playerId;
    public final CopyOnWriteArrayList<Enemy> enemies = new CopyOnWriteArrayList<>();
    public final CopyOnWriteArrayList<Fruit> fruits  = new CopyOnWriteArrayList<>();

    // “Velocidad lógica” de enemigos para este jugador (pasos por tick)
    public int enemySpeedSteps = 1;

    // Meta y spawn (ajústalos a tu mapa real)
    public final int spawnX = 0, spawnY = 0;
    public final int goalX  = 10, goalY  = 10;

    public GameSession(int playerId) { this.playerId = playerId; }

    /** Clona listas plantilla del servidor a esta sesión */
    public void loadFromTemplates(List<Enemy> tplEnemies, List<Fruit> tplFruits) {
        enemies.clear();
        fruits.clear();

        for (Enemy e : tplEnemies) {
            String type = e.getType();
            // usar nombres simples gracias a los imports
            enemies.add("RED".equalsIgnoreCase(type)
                    ? new RedCroc(e.getX(), e.getY())
                    : new BlueCroc(e.getX(), e.getY()));
        }
        for (Fruit f : tplFruits) {
            fruits.add(new SimpleFruit(f.getX(), f.getY(), f.getPoints()));
        }
    }
}


