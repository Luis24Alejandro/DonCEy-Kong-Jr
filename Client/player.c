// Client/player.c
#define _WINSOCK_DEPRECATED_NO_WARNINGS
#include <winsock2.h>
#include <ws2tcpip.h>
#include <windows.h>
#include <stdio.h>
#include <string.h>

#pragma comment(lib, "Ws2_32.lib")

// Lee exactamente una línea (terminada en '\n'), sin incluir el '\n' en out.
// Devuelve longitud leída (>0) o -1 si error / 0 si conexión cerrada.
int recv_line(SOCKET s, char *out, int maxlen) {
    int total = 0;
    while (total < maxlen - 1) {
        char c;
        int n = recv(s, &c, 1, 0);
        if (n == 0) return 0;          // conexión cerrada
        if (n < 0) return -1;          // error
        if (c == '\n') break;          // fin de línea
        if (c != '\r') out[total++] = c; // ignora CR si viene CRLF
    }
    out[total] = '\0';
    return total;
}

int send_line(SOCKET s, const char *line) {
    int len = (int)strlen(line);
    return send(s, line, len, 0);
}

int main(void) {
    WSADATA wsa;
    SOCKET sock = INVALID_SOCKET;
    struct sockaddr_in server;
    char line[1024];

    if (WSAStartup(MAKEWORD(2,2), &wsa) != 0) {
        printf("[C] Error WSAStartup (%d)\n", WSAGetLastError());
        return 1;
    }

    sock = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    if (sock == INVALID_SOCKET) {
        printf("[C] Error socket() (%d)\n", WSAGetLastError());
        WSACleanup();
        return 1;
    }

    memset(&server, 0, sizeof(server));
    server.sin_family = AF_INET;
    server.sin_port = htons(5000);
    server.sin_addr.s_addr = inet_addr("127.0.0.1");

    printf("[C] Conectando a 127.0.0.1:5000 ...\n");
    if (connect(sock, (struct sockaddr*)&server, sizeof(server)) == SOCKET_ERROR) {
        printf("[C] Error connect() (%d)\n", WSAGetLastError());
        closesocket(sock);
        WSACleanup();
        return 1;
    }
    printf("[C] Conectado con éxito.\n");

    // 1) Leer mensaje de bienvenida
    if (recv_line(sock, line, sizeof(line)) > 0) {
        printf("[C] Bienvenida: %s\n", line); // debería ser "WELCOME"
    }

    // 2) PING -> PONG
    send_line(sock, "PING\n");
    if (recv_line(sock, line, sizeof(line)) > 0)
        printf("[C] Respuesta: %s\n", line);  // "PONG"

    // 3) MOVE -> MOVED ...
    send_line(sock, "MOVE 1 2 3\n");
    if (recv_line(sock, line, sizeof(line)) > 0)
        printf("[C] Respuesta: %s\n", line);  // "MOVED 1 2 3"

    // 4) QUIT -> BYE
    send_line(sock, "QUIT\n");
    if (recv_line(sock, line, sizeof(line)) > 0)
        printf("[C] Respuesta: %s\n", line);  // "BYE"

    closesocket(sock);
    WSACleanup();
    printf("[C] Conexión cerrada correctamente.\n");
    return 0;
}

