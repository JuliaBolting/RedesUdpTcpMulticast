import javafx.application.Application
import javafx.application.Platform
import javafx.geometry.Insets
import javafx.scene.Scene
import javafx.scene.control.TextArea
import javafx.scene.layout.VBox
import javafx.stage.Stage
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.Executors
import com.google.gson.Gson
import com.google.gson.JsonObject
import java.io.File

class ServerApp : Application() {
    private lateinit var outputArea: TextArea
    private val clientHandlers = mutableMapOf<Socket, String?>()
    private val executor = Executors.newCachedThreadPool()
    private lateinit var serverSocket: ServerSocket
    private var isRunning = true

    override fun start(primaryStage: Stage) {
        outputArea = TextArea().apply {
            isEditable = false
        }

        val vbox = VBox(10.0, outputArea)
        vbox.padding = Insets(10.0)
        val scene = Scene(vbox, 600.0, 400.0)

        primaryStage.title = "Server"
        primaryStage.scene = scene
        primaryStage.setOnCloseRequest { handleClose() }
        primaryStage.show()

        val serverPort = 12345
        try {
            serverSocket = ServerSocket(serverPort)
            log("Servidor iniciado na porta $serverPort. IP: ${getLocalIpAddress()}")

            val connectionAccepter = ConnectionAccepter(serverSocket, ::handleClient)
            connectionAccepter.start()
        } catch (e: Exception) {
            log("Erro ao iniciar o servidor: ${e.message}")
        }
    }

    private fun getLocalIpAddress(): String {
        return try {
            val inetAddress = InetAddress.getLocalHost()
            inetAddress.hostAddress
        } catch (e: Exception) {
            "Desconhecido"
        }
    }

    private fun handleClient(socket: Socket) {
        val input = socket.getInputStream()
        val output = socket.getOutputStream()
        val gson = Gson()
        var clientAlias: String? = null

        try {
            val buffer = ByteArray(1024)
            var receivedLength = input.read(buffer)

            while (receivedLength != -1) {
                val message = String(buffer, 0, receivedLength)
                log("Mensagem recebida: $message")
                val json = gson.fromJson(message, JsonObject::class.java)

                if (clientAlias == null) {
                    clientAlias = json.get("alias")?.asString
                    if (clientAlias != null) {
                        clientHandlers[socket] = clientAlias
                        log("Cliente identificado: $clientAlias")
                    }
                    receivedLength = input.read(buffer)
                    continue
                }

                when (json.get("command").asString) {
                    "list" -> listFiles(output)
                    "put" -> receiveFile(json, input, output)
                    "get" -> sendFile(json, output)
                    "disconnect" -> {
                        log("Cliente $clientAlias solicitou desconexão")
                        break
                    }
                }
                receivedLength = input.read(buffer)
                log("Dados lidos: $receivedLength bytes")
            }
        } catch (e: Exception) {
            log("Erro na comunicação com o cliente: ${e.message}")
            e.printStackTrace()
        } finally {
            log("Cliente desconectado: ${clientAlias ?: socket.inetAddress.hostAddress}")
            clientHandlers.remove(socket)
            try {
                socket.close()
            } catch (e: Exception) {
                log("Erro ao fechar a conexão: ${e.message}")
            }
        }
    }

    private fun getArchiveDirectory(): File {
        val archiveDir = File("src/archives/")
        if (!archiveDir.exists()) {
            archiveDir.mkdirs()
        }
        return archiveDir
    }

    private fun listFiles(output: OutputStream) {
        val dir = getArchiveDirectory()
        val files = dir.listFiles()?.map { it.name } ?: listOf()
        val gson = Gson()
        val response = gson.toJson(mapOf("files" to files))
        output.write(response.toByteArray())
    }

    private fun receiveFile(json: JsonObject, input: InputStream, output: OutputStream) {
        val fileName = json.get("file").asString
        val expectedHash = json.get("hash").asString

        val file = File(getArchiveDirectory(), fileName)
        file.outputStream().use { fos ->
            val buffer = ByteArray(1024)
            var bytesRead = input.read(buffer)
            while (bytesRead != -1) {
                fos.write(buffer, 0, bytesRead)
                bytesRead = input.read(buffer)
            }
        }

        val fileHash = calculateHash(file)
        val response = if (fileHash == expectedHash) {
            "{\"file\":\"$fileName\",\"operation\":\"put\",\"status\":\"success\"}"
        } else {
            file.delete()
            "{\"file\":\"$fileName\",\"operation\":\"put\",\"status\":\"fail\"}"
        }
        output.write(response.toByteArray())
    }

    private fun sendFile(json: JsonObject, output: OutputStream) {
        val fileName = json.get("file").asString
        val file = File(getArchiveDirectory(), fileName)

        if (file.exists()) {
            val fileHash = calculateHash(file)
            val response = "{\"file\":\"$fileName\",\"operation\":\"get\",\"hash\":\"$fileHash\"}"
            output.write(response.toByteArray())

            file.inputStream().use { fis ->
                val buffer = ByteArray(1024)
                var bytesRead = fis.read(buffer)
                while (bytesRead != -1) {
                    output.write(buffer, 0, bytesRead)
                    bytesRead = fis.read(buffer)
                }
            }
        } else {
            val response = "{\"file\":\"$fileName\",\"operation\":\"get\",\"status\":\"file_not_found\"}"
            output.write(response.toByteArray())
        }
    }

    private fun calculateHash(file: File): String {
        val digest = MessageDigest.getInstance("MD5")
        file.inputStream().use { fis ->
            val buffer = ByteArray(1024)
            var bytesRead = fis.read(buffer)
            while (bytesRead != -1) {
                digest.update(buffer, 0, bytesRead)
                bytesRead = fis.read(buffer)
            }
        }
        return Base64.getEncoder().encodeToString(digest.digest())
    }

    private fun log(message: String) {
        Platform.runLater { outputArea.appendText("$message\n") }
    }

    private fun handleClose() {
        log("Fechando servidor...")

        isRunning = false
        clientHandlers.keys.forEach { socket ->
            try {
                log("Desconectando cliente: ${clientHandlers[socket] ?: socket.inetAddress.hostAddress}")
                socket.close()
            } catch (e: Exception) {
                log("Erro ao desconectar cliente: ${e.message}")
            }
        }

        executor.shutdown()
        try {
            serverSocket.close()
        } catch (e: Exception) {
            log("Erro ao fechar o servidor: ${e.message}")
        }
        log("Servidor fechado.")
    }

    private inner class ConnectionAccepter(private val serverSocket: ServerSocket, private val clientHandler: (Socket) -> Unit) : Thread() {
        override fun run() {
            while (isRunning) {
                try {
                    val clientSocket = serverSocket.accept()
                    log("Cliente conectado: ${clientSocket.inetAddress.hostAddress}")
                    executor.submit { clientHandler(clientSocket) }
                } catch (e: Exception) {
                    if (isRunning) {
                        log("Erro ao aceitar conexão: ${e.message}")
                    }
                }
            }
        }
    }
}

fun main() {
    Application.launch(ServerApp::class.java)
}