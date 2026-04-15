Write-Host "Stopping all Java processes..." -ForegroundColor Yellow
Stop-Process -Name "java" -Force -ErrorAction SilentlyContinue
Start-Sleep -Seconds 3
Write-Host "Starting backend..." -ForegroundColor Green
mvn spring-boot:run
