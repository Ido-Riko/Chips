$ServerIP = "192.168.31.86"
$User = "chips"
$RemoteDir = "/home/chips/chips-backend"

Write-Host "Copying files to $ServerIP..."
# Exclude tests and local venv/git
scp -r ./app ./alembic ./requirements.txt ./.env ./chips-api.openrc ${User}@${ServerIP}:${RemoteDir}/

Write-Host "Deployment transferred."
