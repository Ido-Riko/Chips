$ServerIP = "192.168.31.86"
$User = "idori"
$RemoteDir = "/home/idori/chips-backend"

Write-Host "Creating and claiming remote directory on $ServerIP..."
ssh -t ${User}@${ServerIP} "sudo mkdir -p ${RemoteDir} && sudo chown -R ${User} ${RemoteDir}"

Write-Host "Copying files to $ServerIP..."
# Exclude tests and local venv/git
scp -O -r ./app ./alembic ./requirements.txt ./.env ./chips-api.openrc ${User}@${ServerIP}:${RemoteDir}/

Write-Host "Deployment transferred."
