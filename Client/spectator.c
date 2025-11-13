// Ruta: Client/spectator.c - Cliente Espectador

#include "raylib.h" 
//Definiciones para evitar conflictos con Windows y Sockets
#define NOGDI           
#define NOUSER          
#define WIN32_LEAN_AND_MEAN 
#define NOMINMAX        
#define _WINSOCK_DEPRECATED_NO_WARNINGS
#include <winsock2.h>
#include <ws2tcpip.h>
#include <windows.h>
#include <stdio.h>
#include <string.h>
#include <process.h> 
#pragma comment(lib, "Ws2_32.lib")

volatile int running = 1; 
// Prototipos
int recv_line(SOCKET s, char *out, int maxlen);
int send_line(SOCKET s, const char* txt);
DWORD WINAPI reader_thread(LPVOID p);

// Funciones auxiliares
int recv_line(SOCKET s, char *out, int maxlen){
    int total=0;
    while(total < maxlen-1){
        char c; int n = recv(s, &c, 1, 0);
        if(n==0) return 0; if(n<0) return -1;
        if(c=='\n') break; if(c!='\r') out[total++] = c;
    }
    out[total]='\0'; return total;
}

int send_line(SOCKET s, const char* txt){ 
    return send(s, txt, (int)strlen(txt), 0); 
}

// Hilo Lector (Etiqueta [S] - Mantiene la ventana abierta)
DWORD WINAPI reader_thread(LPVOID p){
    SOCKET sock=*(SOCKET*)p; char line[4096];
    
    while(running){ 
        int n = recv_line(sock, line, sizeof(line));
        
        if(n<=0){ 
            printf("[S] Conexión perdida, cerrando hilo lector. (La ventana permanece abierta)\n"); 
            break; // Cierra solo el hilo de socket
        }

        printf("[S]<- %s\n", line); 
        
        if(strcmp(line,"BYE")==0) running=0; // Cierra la ventana solo con BYE
    } 
    return 0;
}

// Funcion principal
int main(int argc, char** argv){
    int playerId = (argc>=2)? atoi(argv[1]) : 1;

    // Inicialización de Sockets
    WSADATA wsa; WSAStartup(MAKEWORD(2,2), &wsa);
    SOCKET sock = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    struct sockaddr_in server={0};
    server.sin_family=AF_INET; server.sin_port=htons(5000); 
    server.sin_addr.s_addr=inet_addr("127.0.0.1");

    printf("[S] Conectando...\n");
    if(connect(sock,(struct sockaddr*)&server,sizeof(server))!=0){ 
        printf("[S] connect() fallo (%d)\n", WSAGetLastError()); 
        // No retornamos aquí para que la ventana se abra siempre
    }
    printf("[S] Conectado (o intento fallido). Observando jugador %d\n", playerId);

    HANDLE h=CreateThread(NULL,0,reader_thread,&sock,0,NULL);

    char cmd[64]; sprintf(cmd, "SPECTATE %d\n", playerId);
    send_line(sock, cmd); 
    
    // Inicialización de Raylib
    const int screenWidth = 800; 
    const int screenHeight = 600;
    InitWindow(screenWidth, screenHeight, "DonCEy Kong Jr - Espectador"); 
    SetTargetFPS(60); 

    // Bucle Principal 
    while (!WindowShouldClose() && running) { 
        BeginDrawing();
            ClearBackground(DARKGRAY);
            DrawText("CLIENTE ESPECTADOR ACTIVO", 20, 20, 20, RAYWHITE);
            DrawText("ESC para salir", 20, 50, 20, LIGHTGRAY);
        EndDrawing();
    }
    
    // Limpieza
    running = 0; 
    send_line(sock,"QUIT\n"); 
    
    WaitForSingleObject(h,INFINITE);
    closesocket(sock); 
    WSACleanup();
    CloseWindow();
    
    printf("[S] Cliente Espectador cerrado.\n");
    return 0;
}