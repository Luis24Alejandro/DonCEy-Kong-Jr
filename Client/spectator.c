// Ruta: Client/spectator.c
// Cliente espectador: se conecta y envía SPECTATE <playerId>, luego solo imprime OBS ...
#define _WINSOCK_DEPRECATED_NO_WARNINGS
#include <winsock2.h>
#include <ws2tcpip.h>
#include <windows.h>
#include <stdio.h>
#include <string.h>
#pragma comment(lib, "Ws2_32.lib")

int recv_line(SOCKET s, char *out, int maxlen){
    int total=0;
    while(total < maxlen-1){
        char c; int n = recv(s, &c, 1, 0);
        if(n==0) return 0; if(n<0) return -1;
        if(c=='\n') break; if(c!='\r') out[total++] = c;
    }
    out[total]='\0'; return total;
}
int send_line(SOCKET s, const char* txt){ return send(s, txt, (int)strlen(txt), 0); }

int main(int argc, char** argv){
    int playerId = (argc>=2)? atoi(argv[1]) : 1;

    WSADATA wsa; WSAStartup(MAKEWORD(2,2), &wsa);
    SOCKET sock = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    struct sockaddr_in server={0};
    server.sin_family=AF_INET; server.sin_port=htons(5000);
    server.sin_addr.s_addr=inet_addr("127.0.0.1");

    printf("[S] Conectando...\n");
    if(connect(sock,(struct sockaddr*)&server,sizeof(server))!=0){ printf("[S] connect() fallo (%d)\n", WSAGetLastError()); return 1; }
    printf("[S] Conectado. Observando jugador %d\n", playerId);

    char cmd[64]; sprintf(cmd, "SPECTATE %d\n", playerId);
    send_line(sock, cmd);

    char line[4096];
    while(1){
        int n = recv_line(sock, line, sizeof(line));
        if(n<=0){ printf("[S] Servidor cerró.\n"); break; }

        // Filtrar STATE (el servidor ya no debería mandarlos al espectador, pero por si acaso)
        if (strncmp(line, "STATE ", 6) == 0) continue;

        // Verás WELCOME, OK WAITING/OK SPECTATING, OBS ..., OBS_END, ERR ..., BYE
        printf("[S]<- %s\n", line);
        if(strcmp(line,"BYE")==0) break;
    }

    closesocket(sock); WSACleanup();
    return 0;
}

