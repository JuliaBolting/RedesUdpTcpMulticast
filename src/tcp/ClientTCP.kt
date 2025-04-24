import com.google.gson.Gson
import com.google.gson.JsonObject
import javafx.application.Application
import javafx.geometry.Insets
import javafx.scene.Scene
import javafx.scene.control.*
import javafx.scene.layout.GridPane
import javafx.stage.Stage
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.Socket
import java.security.MessageDigest
import java.util.*

class ClientApp : Application() {
    private var outputArea: TextArea? = null
    private var output: OutputStream? = null
    private var input: InputStream? = null
    private var socket: Socket? = null
    private lateinit var aliasField: TextField
    private lateinit var commandComboBox: ComboBox<String>
    private lateinit var fileNameField: TextField
    private lateinit var actionButton: Button
    private lateinit var connectButton: Button
    private var isConnected = false

    override fun start(primaryStage: Stage) {
        val gson = Gson()

        val ipAddressField = TextField()
        val portField = TextField()
        aliasField = TextField()
        fileNameField = TextField()
        fileNameField.text = "src/archives/reset.txt"
        commandComboBox = ComboBox<String>().apply {
            items.addAll("list", "put", "get")
            value = "list" // Set default value
            valueProperty().addListener { _, _, newValue ->
                fileNameField.isDisable = newValue != "put" && newValue != "get"
            }
        }
        outputArea = TextArea().apply {
            isEditable = false
            prefHeight = 300.0 // Increased height for better visibility
        }

        connectButton = Button("Connect").apply {
            setOnAction { toggleConnection(ipAddressField.text, portField.text.toIntOrNull(), aliasField.text) }
        }

        actionButton = Button("Send Command").apply {
            isDisable = true
            setOnAction { handleCommand(gson) }
        }

        ipAddressField.promptText = "IP do servidor"
        ipAddressField.text = InetAddress.getLocalHost().hostAddress
        portField.promptText = "Porta"
        aliasField.promptText = "Apelido"
        fileNameField.promptText = "Nome do arquivo"

        val gridPane = GridPane().apply {
            padding = Insets(10.0)
            hgap = 10.0
            vgap = 10.0
            add(Label("IP do servidor:"), 0, 0)
            add(ipAddressField, 1, 0)
            add(Label("Porta:"), 0, 1)
            add(portField, 1, 1)
            add(Label("Apelido:"), 0, 2)
            add(aliasField, 1, 2)
            add(connectButton, 1, 3)
            add(Label("Comando:"), 0, 4)
            add(commandComboBox, 1, 4)
            add(Label("Nome do arquivo:"), 0, 5)
            add(fileNameField, 1, 5)
            add(actionButton, 1, 6)
            add(outputArea, 0, 7, 2, 1)
        }

        primaryStage.scene = Scene(gridPane, 600.0, 400.0)
        primaryStage.title = "Client"
        primaryStage.show()
    }

    private fun toggleConnection(ipAddress: String, port: Int?, alias: String) {
        if (isConnected) {
            disconnectFromServer()
        } else {
            connectToServer(ipAddress, port, alias)
        }
    }

    private fun connectToServer(ipAddress: String, port: Int?, alias: String) {
        try {
            socket = Socket(ipAddress, port ?: 12345)
            output = socket?.getOutputStream()
            input = socket?.getInputStream()
            val connectionMessage = "{\"alias\":\"$alias\"}"
            output?.write(connectionMessage.toByteArray())
            outputArea?.appendText("Conectado ao servidor $ipAddress:$port\n")
            actionButton.isDisable = false
            connectButton.text = "Disconnect"
            isConnected = true
        } catch (e: Exception) {
            outputArea?.appendText("Erro ao conectar ao servidor: ${e.message}\n")
            e.printStackTrace()
        }
    }

    private fun disconnectFromServer() {
        try {
            socket?.close()
            outputArea?.appendText("Desconectado do servidor.\n")
            actionButton.isDisable = true
            connectButton.text = "Connect"
            isConnected = false
        } catch (e: Exception) {
            outputArea?.appendText("Erro ao desconectar do servidor: ${e.message}\n")
            e.printStackTrace()
        }
    }

    private fun handleCommand(gson: Gson) {
        try {
            val command = commandComboBox.value
            val fileName = fileNameField.text

            when (command) {
                "list" -> {
                    sendCommand("list")
                    val response = readResponse()
                    outputArea?.appendText("Resposta do servidor: $response\n")
                    val jsonResponse = gson.fromJson(response, JsonObject::class.java)
                    val files = jsonResponse.getAsJsonArray("files")
                    outputArea?.appendText("Arquivos no servidor: ${files.joinToString(", ")}\n")
                }
                "put" -> {
                    val file = File(fileName)
                    if (file.exists()) {
                        val fileHash = calculateHash(file)
                        sendFile(file, fileHash)
                        val response = readResponse()
                        outputArea?.appendText("Resposta do servidor: $response\n")
                    } else {
                        outputArea?.appendText("Arquivo $fileName não encontrado.\n")
                    }
                }
                "get" -> {
                    sendCommand("get", fileName)
                    val response = readResponse()
                    outputArea?.appendText("Resposta do servidor: $response\n")
                    val jsonResponse = gson.fromJson(response, JsonObject::class.java)
                    if (jsonResponse.has("hash")) {
                        val fileHash = jsonResponse.get("hash").asString
                        receiveFile(fileName, fileHash)
                        outputArea?.appendText("Arquivo $fileName recebido.\n")
                    } else {
                        outputArea?.appendText("Erro ao obter arquivo: $response\n")
                    }
                }
            }
        } catch (e: Exception) {
            outputArea?.appendText("Erro ao processar comando: ${e.message}\n")
            e.printStackTrace()
        }
    }

    private fun sendCommand(command: String, vararg params: String) {
        try {
            val json = JsonObject().apply {
                addProperty("command", command)
                if (params.isNotEmpty()) addProperty("file", params[0])
                if (params.size > 1) addProperty("hash", params[1])
            }
            output?.write(json.toString().toByteArray())
        } catch (e: Exception) {
            outputArea?.appendText("Erro ao enviar comando: ${e.message}\n")
            e.printStackTrace()
        }
    }

    private fun readResponse(): String {
        return try {
            val buffer = ByteArray(1024)
            val length: Int
            try {
                length = input?.read(buffer) ?: -1
            } catch (e: Exception) {
                outputArea?.appendText("Erro ao ler do InputStream: ${e.message}\n")
                e.printStackTrace()
                return ""
            }
            if (length == -1) {
                ""
            } else {
                String(buffer, 0, length)
            }
        } catch (e: Exception) {
            outputArea?.appendText("Erro ao ler resposta: ${e.message}\n")
            e.printStackTrace()
            ""
        }
    }

    private fun sendFile(file: File, fileHash: String) {
        try {
            val json = JsonObject().apply {
                addProperty("command", "put")
                addProperty("file", file.name)
                addProperty("hash", fileHash)
            }
            sendCommand(json.toString())
            file.inputStream().use { fis ->
                val buffer = ByteArray(1024)
                var bytesRead = fis.read(buffer)
                while (bytesRead != -1) {
                    output?.write(buffer, 0, bytesRead)
                    bytesRead = fis.read(buffer)
                }
            }
        } catch (e: Exception) {
            outputArea?.appendText("Erro ao enviar arquivo: ${e.message}\n")
            e.printStackTrace()
        }
    }

    private fun receiveFile(fileName: String, expectedHash: String) {
        try {
            val file = File(fileName)
            if (!file.exists()) {
                outputArea?.appendText("Arquivo $fileName não existe.\n")
                return
            }

            file.outputStream().use { fos ->
                val buffer = ByteArray(1024)
                var bytesRead: Int
                while (input?.read(buffer).also { bytesRead = it ?: -1 } != -1) {
                    outputArea?.appendText("Recebido $bytesRead bytes.\n")
                    fos.write(buffer, 0, bytesRead)
                    break
                }

            }

            val fileHash = calculateHash(file)
            if (fileHash != expectedHash) {
                file.delete()
                outputArea?.appendText("Arquivo $fileName corrompido. Hash não corresponde.\n")
            } else {
                println("Calculo de hash perfeito: "+ fileHash + " hash recebido: "+ expectedHash)
                outputArea?.appendText("Arquivo $fileName recebido com sucesso.\n")
            }
        } catch (e: Exception) {
            outputArea?.appendText("Erro ao receber arquivo: ${e.message}\n")
            e.printStackTrace()
        }
    }

    private fun calculateHash(file: File): String {
        return try {
            val digest = MessageDigest.getInstance("MD5")
            file.inputStream().use { fis ->
                val buffer = ByteArray(1024)
                var bytesRead = fis.read(buffer)
                while (bytesRead != -1) {
                    digest.update(buffer, 0, bytesRead)
                    bytesRead = fis.read(buffer)
                }
            }
            Base64.getEncoder().encodeToString(digest.digest())
        } catch (e: Exception) {
            outputArea?.appendText("Erro ao calcular hash: ${e.message}\n")
            e.printStackTrace()
            ""
        }
    }
}

fun main() {
    Application.launch(ClientApp::class.java)
}