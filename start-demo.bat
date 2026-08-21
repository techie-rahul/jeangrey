@echo off
echo ========================================================
echo  Starting ResQMesh Server (Port 5000) & Dashboard (Port 3000)
echo ========================================================

start "ResQMesh Backend" cmd /k "cd server && npm start"
start "ResQMesh Dashboard" cmd /k "cd dashboard && npm run dev"

echo Services launched! 
echo Backend: http://localhost:5000
echo Dashboard: http://localhost:3000
