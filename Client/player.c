// Ruta: Client/player.c
// Cliente C (Windows - Winsock)
#define _WINSOCK_DEPRECATED_NO_WARNINGS
#include <winsock2.h>
#include <ws2tcpip.h>
#include <stdio.h>

#pragma comment(lib, "Ws2_32.lib")

int main(void) {
    WSADATA wsa;
    SOCKET sock = INVALID_SOCKET;
    struct sockaddr_in server;
    char sendbuf[] = "Hola desde C\n";  // termina en \n para que Java .readLine() funcione
    char recvbuf[1024];
    int recvlen;

    if (WSAStartup(MAKEWORD(2,2), &wsa) != 0) {
        printf("[C] Error WSAStartup\n");
        return 1;
    }

    sock = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    if (sock == INVALID_SOCKET) {
        printf("[C] Error socket()\n");
        WSACleanup();
        return 1;
    }

    server.sin_family = AF_INET;
    server.sin_port = htons(5000);
    server.sin_addr.s_addr = inet_addr("127.0.0.1");

    printf("[C] Conectando a 127.0.0.1:5000 ...\n");
    if (connect(sock, (struct sockaddr*)&server, sizeof(server)) == SOCKET_ERROR) {
        printf("[C] Error connect()\n");
        closesocket(sock);
        WSACleanup();
        return 1;
    }

    // Enviar
    if (send(sock, sendbuf, (int)strlen(sendbuf), 0) == SOCKET_ERROR) {
        printf("[C] Error send()\n");
        closesocket(sock);
        WSACleanup();
        return 1;
    }
    printf("[C] Enviado: %s", sendbuf);

    // Recibir
    recvlen = recv(sock, recvbuf, sizeof(recvbuf)-1, 0);
    if (recvlen > 0) {
        recvbuf[recvlen] = '\0';
        printf("[C] Recibido: %s", recvbuf);
    } else {
        printf("[C] No se recibió respuesta\n");
    }

    closesocket(sock);
    WSACleanup();
    return 0;
}