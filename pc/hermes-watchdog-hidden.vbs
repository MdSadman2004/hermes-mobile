' Console-free OS launcher. The venv pythonw here is a console-subsystem shim.
' Keep this wrapper tiny; task duration/exit code follow the real watchdog.
Option Explicit
Dim shell, command, port, code
Set shell = CreateObject("WScript.Shell")
port = "9119"
If WScript.Arguments.Count > 0 Then port = WScript.Arguments(0)
If Not IsNumeric(port) Then WScript.Quit 2
command = """D:\.hermes\hermes-agent\venv\Scripts\python.exe"" ""D:\HermesMobile\pc\hermes-watchdog-headless.py"" --port " & CStr(CLng(port))
code = shell.Run(command, 0, True)
WScript.Quit code
