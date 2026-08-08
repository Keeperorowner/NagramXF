"""dev_server — plugin development server over a local TCP socket.

A JSON-over-TCP server on ``DEFAULT_PORT`` (42690) that accepts a
command per line/JSON-object and replies with a JSON response.  Supported
commands (dispatched by the ``action`` field):

* ``ping``
* ``get_plugins``
* ``enable_plugin`` / ``disable_plugin``
* ``reload_plugin``
* ``write_plugin``
* ``remove_plugin``
* ``start_debugger`` / ``stop_debugger``

The app only calls ``DevServer.start_server()`` / ``DevServer.stop_server()``;
everything else happens on a background daemon thread.
"""
from __future__ import annotations

import json
import os
import shutil
import socket
import sys
import tempfile
import threading
import traceback

from java import jclass

from android_utils import log as _log

PluginsController = jclass("com.exteragram.messenger.plugins.PluginsController")
PythonPluginsEngine = jclass("com.exteragram.messenger.plugins.PythonPluginsEngine")
Utilities = jclass("org.telegram.messenger.Utilities")
FileLog = jclass("org.telegram.messenger.FileLog")


class Callback:
    def __init__(self, fn):
        self._fn = fn

    def run(self, arg):
        if self._fn is not None:
            self._fn(arg)


class DebuggerEventListener:
    def __init__(self, host, port, platform):
        self.host = host
        self.port = port
        self.platform = platform

    def app_event(self, *args, **kwargs):
        pass

    def on_app_event(self, event_type):
        pass


class DevServer:
    DEFAULT_HOST = ""
    DEFAULT_PORT = 42690
    BUFFER_SIZE = 4096
    SOCKET_TIMEOUT = 600
    PYCHARM_DEBUGGER_DIR = None

    _is_running = False
    _server_socket = None
    _server_thread = None
    _active_debugging = False
    _active_platform = None

    # ---- lifecycle ---------------------------------------------------------

    @classmethod
    def start_server(cls, host=DEFAULT_HOST, port=DEFAULT_PORT):
        if cls._is_running:
            if cls._server_thread is not None and cls._server_thread.is_alive():
                _log("Dev server already running on %s:%d" % (host, port))
                return
        cls._is_running = True
        cls._server_thread = threading.Thread(
            target=cls._server_thread_function,
            args=(host, port),
            name="plugins-dev-server",
            daemon=True,
        )
        cls._server_thread.start()
        _log("Dev server started in background thread on %s:%d" % (host, port))

    @classmethod
    def stop_server(cls):
        if not cls._is_running:
            return
        cls._is_running = False
        if cls._active_debugging:
            if cls._active_platform is not None:
                try:
                    cls.stop_remote_debugging(cls._active_platform)
                except Exception:
                    pass
        if cls._server_socket is not None:
            try:
                cls._server_socket.close()
            except Exception as e:
                _log("Error closing server socket: %s" % e)
            cls._server_socket = None

    # ---- server thread -----------------------------------------------------

    @classmethod
    def _server_thread_function(cls, host, port):
        server_socket = None
        try:
            server_socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            server_socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            server_socket.bind((host, port))
            server_socket.listen(5)
            cls._server_socket = server_socket
            _log("Dev server listening on %s:%d" % (host, port))
            while cls._is_running:
                try:
                    client_socket, client_address = server_socket.accept()
                    _log("Accepted connection from %s" % str(client_address))
                    cls._handle_client(client_socket)
                except Exception as e:
                    if cls._is_running:
                        _log("Accept error: %s" % e)
        except Exception as e:
            _log("Server error: %s" % e)
        finally:
            cls._is_running = False
            cls._server_socket = None
            if server_socket is not None:
                try:
                    server_socket.close()
                except Exception:
                    pass

    @classmethod
    def _handle_client(cls, client_socket):
        buffer = b""
        try:
            client_socket.settimeout(cls.SOCKET_TIMEOUT)
            while cls._is_running:
                try:
                    data = client_socket.recv(cls.BUFFER_SIZE)
                except socket.timeout:
                    break
                except Exception:
                    break
                if not data:
                    break
                buffer += data
                buffer = cls._process_buffer(buffer, client_socket)
        except Exception as e:
            _log("Error handling client: %s" % e)
        finally:
            try:
                client_socket.close()
            except Exception:
                pass

    @classmethod
    def _process_buffer(cls, buffer, client_socket):
        data = buffer
        while True:
            if isinstance(data, bytes):
                try:
                    decoded = data.decode("utf-8")
                except Exception:
                    return data
            else:
                decoded = data
            stripped = decoded.lstrip()
            if not stripped:
                return b""
            try:
                json_obj, end_pos = json.JSONDecoder().raw_decode(stripped)
            except Exception:
                # Incomplete JSON: wait for more bytes.
                return data
            try:
                cls._process_command(json_obj, client_socket)
            except Exception as e:
                _log("Error processing command: %s" % e)
            remaining = stripped[end_pos:]
            if not remaining.lstrip():
                return b""
            data = remaining.encode("utf-8")

    # ---- command dispatch --------------------------------------------------

    @classmethod
    def _process_command(cls, command, client_socket):
        if not isinstance(command, dict):
            return
        action = command.get("@")
        request_id = command.get("#")
        _log("Received command: %s" % action)
        result = None
        try:
            if action == "ping":
                result = cls._handle_ping(command)
            elif action == "get_plugins":
                result = cls._handle_get_plugins(command)
            elif action == "enable_plugin":
                result = cls._handle_enable_plugin(command)
            elif action == "disable_plugin":
                result = cls._handle_disable_plugin(command)
            elif action == "reload_plugin":
                result = cls._handle_reload_plugin(command)
            elif action == "write_plugin":
                result = cls._handle_write_plugin(command)
            elif action == "remove_plugin":
                result = cls._handle_remove_plugin(command)
            elif action == "start_debugger":
                result = cls._handle_start_debugger(command)
            elif action == "stop_debugger":
                result = cls._handle_stop_debugger(command)
            else:
                _log("Unknown action: %s" % action)
                result = {"success": False, "error": "Unknown action: %s" % action}
        except Exception as e:
            traceback.print_exc()
            result = {"success": False, "error": str(e)}

        try:
            response_data = {"#": request_id}
            if isinstance(result, dict):
                response_data.update(result)
            else:
                response_data["result"] = result
            client_socket.sendall(json.dumps(response_data).encode("utf-8"))
        except Exception as e:
            _log("Error sending response: %s" % e)

    # ---- command handlers --------------------------------------------------

    @classmethod
    def _handle_ping(cls, command):
        return {"pong": True}

    @staticmethod
    def _get_plugins_map():
        plugins = {}
        try:
            controller = PluginsController.getInstance()
            for plugin in controller.plugins.values():
                plugins[str(plugin.getId())] = {
                    "id": str(plugin.getId()),
                    "name": str(plugin.getName()),
                    "description": str(plugin.getDescription()),
                    "version": str(plugin.getVersion()),
                    "author": str(plugin.getAuthor()),
                    "enabled": bool(plugin.isEnabled()),
                }
        except Exception as e:
            _log("Failed to build plugins map: %s" % e)
        return plugins

    @classmethod
    def _handle_get_plugins(cls, command):
        return {"success": True, "plugins": cls._get_plugins_map()}

    @classmethod
    def _handle_enable_plugin(cls, command):
        plugin_id = command.get("plugin_id")
        if not plugin_id:
            return {"success": False, "error": "missing plugin_id"}
        _log("Enabling plugin " + plugin_id)
        PluginsController.getInstance().setPluginEnabled(plugin_id, True, None)
        return {"success": True}

    @classmethod
    def _handle_disable_plugin(cls, command):
        plugin_id = command.get("plugin_id")
        if not plugin_id:
            return {"success": False, "error": "missing plugin_id"}
        _log("Disabling plugin " + plugin_id)
        PluginsController.getInstance().setPluginEnabled(plugin_id, False, None)
        return {"success": True}

    @classmethod
    def _handle_reload_plugin(cls, command):
        plugin_id = command.get("plugin_id")
        if not plugin_id:
            return {"success": False, "error": "missing plugin_id"}
        _log("Reloading plugin " + plugin_id)
        path = cls._reload_plugin(plugin_id)
        return {"success": path is not None, "plugin_path": path}

    @classmethod
    def _reload_plugin(cls, plugin_id):
        controller = PluginsController.getInstance()
        plugin_path = controller.getPluginPath(plugin_id)
        if not plugin_path or not os.path.exists(plugin_path):
            _log("Cannot reload plugin '%s': file not found." % plugin_id)
            return None
        try:
            PythonPluginsEngine.INSTANCE.loadPlugin(str(plugin_id), str(plugin_path))
            _log("Plugin '%s' reloaded" % plugin_id)
            return plugin_path
        except Exception as e:
            _log("Error during _reload_plugin for '%s': %s" % (plugin_id, e))
            return None

    @classmethod
    def _handle_write_plugin(cls, command):
        content = command.get("content")
        plugin_id = command.get("plugin_id")
        if content is None:
            return {"success": False, "error": "missing content"}
        try:
            suffix = ".py"
            if isinstance(content, dict) and content.get("plugin") is not None:
                content = content["plugin"]
            name = str(plugin_id or "plugin").replace(os.sep, "_")
            with tempfile.NamedTemporaryFile(
                    prefix="plugin_", suffix=suffix, delete=False) as tmp_file:
                temp_plugin_path = tmp_file.name
                if isinstance(content, (bytes, bytearray)):
                    tmp_file.write(content)
                else:
                    tmp_file.write(str(content).encode("utf-8"))
            _log("Plugin content written to temporary file: %s" % temp_plugin_path)
            try:
                PythonPluginsEngine.INSTANCE.loadPlugin(str(plugin_id), temp_plugin_path)
                _log("Plugin '%s' installed/updated via loader" % plugin_id)
            except Exception as e:
                _log("Error loading plugin '%s': %s" % (plugin_id, e))
            return {"success": True, "temp_plugin_path": temp_plugin_path}
        except Exception as e:
            _log("Error writing plugin " + str(plugin_id) + ": " + str(e))
            return {"success": False, "error": str(e)}

    @classmethod
    def _handle_remove_plugin(cls, command):
        plugin_id = command.get("plugin_id")
        if not plugin_id:
            return {"success": False, "error": "missing plugin_id"}
        _log("Removing plugin " + plugin_id)
        PluginsController.getInstance().deletePlugin(plugin_id, None)
        return {"success": True}

    @classmethod
    def _handle_start_debugger(cls, command):
        host = command.get("host")
        port = command.get("port")
        platform = command.get("platform")
        if host is None or port is None or platform is None:
            return {"success": False, "error": "missing host/port/platform"}
        try:
            cls.setup_remote_debugging(host, int(port), str(platform))
            return {"success": True}
        except Exception as e:
            _log("Error setting up remote debugger: %s" % e)
            return {"success": False, "error": str(e)}

    @classmethod
    def _handle_stop_debugger(cls, command):
        platform = command.get("platform")
        try:
            cls.stop_remote_debugging(platform)
            return {"success": True}
        except Exception as e:
            _log("Error stopping remote debugger: %s" % e)
            return {"success": False, "error": str(e)}

    # ---- remote debugging --------------------------------------------------

    @classmethod
    def setup_remote_debugging(cls, host, port, platform):
        platform_str = str(platform).lower()
        _log("Starting remote debugger on %s:%d, platform: %s" % (host, port, platform))
        if platform_str == "pycharm":
            cls._setup_pycharm_remote_debugger(host, port)
            cls._active_platform = "PyCharm"
        elif platform_str in ("vscode", "vs_code", "code"):
            cls._setup_vscode_remote_debugger(host, port)
            cls._active_platform = "VSCode"
        else:
            raise ValueError("Unsupported debugger platform: " + str(platform))
        cls._active_debugging = True

    @classmethod
    def stop_remote_debugging(cls, platform):
        if platform is None and cls._active_platform is None:
            return
        platform_str = str(platform or cls._active_platform).lower()
        try:
            if platform_str in ("pycharm",):
                cls._stop_pycharm_remote_debugging()
            elif platform_str in ("vscode", "vs_code", "code"):
                cls._stop_vscode_remote_debugger()
        except Exception as e:
            _log("Error stopping remote debugger: %s" % e)
        cls._active_debugging = False
        cls._active_platform = None

    @classmethod
    def _setup_pycharm_remote_debugger(cls, host, port):
        _log("Setting up PyCharm remote debugger on %s:%d" % (host, port))
        if cls.PYCHARM_DEBUGGER_DIR and os.path.isdir(cls.PYCHARM_DEBUGGER_DIR):
            if cls.PYCHARM_DEBUGGER_DIR not in sys.path:
                sys.path.append(cls.PYCHARM_DEBUGGER_DIR)
        import pydevd_pycharm
        pydevd_pycharm.settrace(host, port=port, stdoutToServer=True, stderrToServer=True, suspend=False)

    @classmethod
    def _setup_vscode_remote_debugger(cls, host, port):
        _log("Connecting VS Code remote debugger to %s:%d" % (host, port))
        import debugpy
        debugpy.listen((host, int(port)))

    @classmethod
    def _stop_pycharm_remote_debugging(cls):
        try:
            import pydevd_pycharm
            pydevd_pycharm.stoptrace()
        except Exception as e:
            _log("Error stopping PyCharm remote debugger: %s" % e)

    @classmethod
    def _stop_vscode_remote_debugger(cls):
        try:
            import debugpy._vendored.pydevd.pydevd as pydevd
            pydevd.stoptrace()
        except Exception as e:
            _log("Error stopping VS Code remote debugger: %s" % e)
