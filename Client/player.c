#define WIN32_LEAN_AND_MEAN   // menos cosas de windows.h
#define NOMINMAX              // evita macros min/max
#define NOGDI                 // NO cargar GDI (Rectangle, etc.)
#define NOUSER                // NO cargar funciones de user32 (CloseWindow, ShowCursor, DrawText, etc.)
#define NOSOUND               // NO cargar PlaySound de winmm

#include <winsock2.h>   // SIEMPRE antes de windows.h
#include <ws2tcpip.h>
#include <windows.h>

#include <stdio.h>
#include <string.h>

#include "constants.h"
#include "raylib.h"
#include "map.h"

#pragma comment(lib, "ws2_32.lib")

// =======================
//   ESTADO DEL JUGADOR
// =======================
typedef struct {
    int id;
    int x;
    int y;
    int lastAck;
    int score;
    int round;
    int connected;
    int gameOver;
} PlayerState;

static PlayerState player = {0};

// =======================
//   UTIL RED
// =======================
int recv_line(SOCKET s, char *out, int maxlen){
    int total=0;
    while(total < maxlen-1){
        char c; int n = recv(s, &c, 1, 0);
        if(n<=0) return n;
        if(c=='\n') break;
        if(c!='\r') out[total++] = c;
    }
    out[total] = '\0';
    return total;
}

DWORD WINAPI reader_thread(LPVOID p){
    SOCKET sock = *(SOCKET*)p;
    char line[LINE_MAX];

    while(player.connected){
        int n = recv_line(sock, line, sizeof(line));
        if(n <= 0){
            player.connected = 0;
            break;
        }

        printf("[PLAYER]<- %s\n", line);

        // ASSIGN id
        if(strncmp(line, "ASSIGN ", 7) == 0){
            player.id = atoi(line + 7);
        }
        // STATE t id x y ack score round;
        else if(strncmp(line, "STATE ", 6) == 0){
            char *p = strchr(line, ' ');
            if(!p) continue;
            p++; // tick
            p = strchr(p, ' ');
            if(!p) continue;
            p++; // inicio lista

            int id,x,y,ack,score,round,consumed;
            // Por simplicidad, asumimos 1 jugador
            if(sscanf(p, "%d %d %d %d %d %d;%n",
                      &id,&x,&y,&ack,&score,&round,&consumed) == 6){
                if(id == player.id){
                    player.x = x;
                    player.y = y;
                    player.lastAck = ack;
                    player.score = score;
                    player.round = round;
                }
            }
        }
        // SCORE id score
        else if(strncmp(line, "SCORE ", 6) == 0){
            int id, score;
            if(sscanf(line+6, "%d %d", &id, &score) == 2){
                if(id == player.id){
                    player.score = score;
                }
            }
        }
        // ROUND id round speed
        else if(strncmp(line, "ROUND ", 6) == 0){
            int id, round, speed;
            if(sscanf(line+6, "%d %d %d", &id, &round, &speed) == 3){
                if(id == player.id){
                    player.round = round;
                }
            }
        }
        // LOSE id finalScore
        else if(strncmp(line, "LOSE ", 5) == 0){
            int id, finalScore;
            if(sscanf(line+5, "%d %d", &id, &finalScore) == 2){
                if(id == player.id){
                    player.score = finalScore;
                    player.gameOver = 1;
                    // NO cerramos la conexión; dejamos que el jugador vea la pantalla final
                }
            }
        }

    }
    return 0;
}

// =======================
//   MAIN
// =======================
int main(void){
    // ---- init red ----
    WSADATA wsa;
    WSAStartup(MAKEWORD(2,2), &wsa);

    SOCKET sock = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);

    struct sockaddr_in srv = {0};
    srv.sin_family = AF_INET;
    srv.sin_port = htons(HOST_PORT);
    srv.sin_addr.s_addr = inet_addr(HOST_IP);

    printf("[C] Conectando al servidor %s:%d ...\n", HOST_IP, HOST_PORT);
    if(connect(sock,(struct sockaddr*)&srv,sizeof(srv))!=0){
        printf("[C] Error conectando (%d)\n", WSAGetLastError());
        return 1;
    }
    printf("[C] Conectado!\n");

    player.connected = 1;
    player.gameOver = 0;

    CreateThread(NULL,0,reader_thread,&sock,0,NULL);

    // JOIN
    send(sock, "JOIN Jugador\n", (int)strlen("JOIN Jugador\n"), 0);

    // ---- init Raylib ----
    const int screenWidth  = 800;
    const int screenHeight = 600;
    InitWindow(screenWidth, screenHeight, "DonCEy-Kong Jr - Jugador");
    SetTargetFPS(60);

        // Cargar fondo
    Texture2D bg = LoadTexture("assets/dkjr_bg.png");

    // Escala para que el fondo ocupe toda la ventana
    float scaleX = (float)screenWidth  / bg.width;
    float scaleY = (float)screenHeight / bg.height;
    // Si quieres mantener proporción, puedes usar el menor de los dos:
    float scale = (scaleX < scaleY) ? scaleX : scaleY;


    // offset para centrar el mapa
    const int cellSize = 32;   // 32 suele encajar mejor con sprites viejos; puedes dejar 40 si lo prefieres

    // Estas son las coordenadas EN PANTALLA donde empieza la rejilla (esquina inferior izquierda del mapa)
    const int offsetX = 80;    // AJUSTAR ESTOS DOS A OJO
    const int offsetY = 120;   // HASTA QUE LOS BLOQUES CAIGAN DONDE DEBE


    int seq = 1;

    while(!WindowShouldClose() && player.connected){
        // ---- INPUT → enviar al servidor ----
        int dx = 0, dy = 0;

        // 1) Caminar normal con flechas (si quieres mantenerlo)
        if (IsKeyPressed(KEY_RIGHT)) dx = 1;
        if (IsKeyPressed(KEY_LEFT))  dx = -1;
        if (IsKeyPressed(KEY_UP))    dy = 1;
        if (IsKeyPressed(KEY_DOWN))  dy = -1;

        if (dx != 0 || dy != 0) {
            char msg[SMALL_BUF];
            sprintf(msg, "INPUT %d %d %d\n", seq++, dx, dy);
            send(sock, msg, (int)strlen(msg), 0);
        }

        // 2) SALTO con ESPACIO + dirección
        int jdx = 0, jdy = 0;
        if (IsKeyPressed(KEY_SPACE)) {
            // Dirección del salto según flechas que estén pulsadas AHORA
            if (IsKeyDown(KEY_RIGHT))      jdx = 1;
            else if (IsKeyDown(KEY_LEFT))  jdx = -1;
            else if (IsKeyDown(KEY_UP))    jdy = 1;
            // (si no hay flecha, no se hace nada)

            if (jdx != 0 || jdy != 0) {
                char msg[SMALL_BUF];
                sprintf(msg, "INPUT %d %d %d\n", seq++, jdx, jdy);
                send(sock, msg, (int)strlen(msg), 0);
            }
        }


        // Tecla para salir de la partida (por ejemplo ENTER o ESC)
        if (IsKeyPressed(KEY_ENTER) || IsKeyPressed(KEY_ESCAPE)) {
            const char *quitMsg = "QUIT\n";
            send(sock, quitMsg, (int)strlen(quitMsg), 0);
            player.connected = 0;   // esto hará que el while termine
        }

        // ---- DIBUJO ----
        BeginDrawing();
        ClearBackground(BLACK);

        // Dibujar el fondo del juego
        DrawTextureEx(bg, (Vector2){0, 0}, 0.0f, scale, WHITE);

        // Dibuja tiles del mapa
        for(int y=0; y<MAP_H; y++){
            for(int x=0; x<MAP_W; x++){
                char t = MapGetTile(x, y);
                int px = offsetX + x * cellSize;
                int py = offsetY + (MAP_H-1 - y) * cellSize;

                Color c;
                if (t == '.') continue;
                else if (t == 'W') c = BLUE;           // agua
                else if (t == 'T') c = BROWN;          // tierra que sale del agua
                else if (t == '=') c = BROWN;          // plataforma
                else if (t == '|') c = DARKGREEN;      // liana
                else if (t == 'G') c = RED;            // jaula DK
                else if (t == 'S') c = BLUE;           // spawn (lo puedes pintar distinto si quieres)
                else c = DARKBROWN;

                DrawRectangle(px, py, cellSize, cellSize, Fade(c, 0.5f)); // 0.5f = semitransparente para ver el fondo


            }
        }

        // Dibuja al jugador (Jr)
        int px = offsetX + player.x * cellSize;
        int py = offsetY + (MAP_H-1 - player.y) * cellSize;
        DrawRectangle(px+4, py+4, cellSize-8, cellSize-8, YELLOW);

        // HUD
        DrawText(TextFormat("ID: %d", player.id), 20, 20, 20, RAYWHITE);
        DrawText(TextFormat("Score: %d", player.score), 20, 50, 20, RAYWHITE);
        DrawText(TextFormat("Round: %d", player.round), 20, 80, 20, RAYWHITE);

        if(player.gameOver){
            DrawText("GAME OVER", screenWidth/2 - 120, screenHeight/2 - 20, 40, RED);
        }

        EndDrawing();
    }

    // Enviar QUIT solo si salimos con la ventana aún conectada
    if(player.connected){
        send(sock, "QUIT\n", (int)strlen("QUIT\n"), 0);
    }

    CloseWindow();
    closesocket(sock);
    WSACleanup();
    return 0;
}
