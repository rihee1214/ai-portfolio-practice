@ECHO OFF
@SETLOCAL
@SET "GSD_NODE="
@IF DEFINED GSD_NODE_PATH IF EXIST "%GSD_NODE_PATH%" SET "GSD_NODE=%GSD_NODE_PATH%"
@IF NOT DEFINED GSD_NODE IF EXIST "D:/Tools/SDK/Node.js-20.12.1/node.exe" SET "GSD_NODE=D:/Tools/SDK/Node.js-20.12.1/node.exe"
@IF NOT DEFINED GSD_NODE FOR /F "delims=" %%N IN ('where $PATH:node.exe 2^>NUL') DO @IF NOT DEFINED GSD_NODE SET "GSD_NODE=%%N"
@"%GSD_NODE%" "D:/Workspace/ai-portfolio-practice/.codex/hooks/gsd-check-update.js" %*
