# Cloud Run / any container host
# 1. Build: docker build -f infrastructure/Dockerfile -t roxstar-backend .
# 2. Push to Artifact Registry / ECR / ACR
# 3. Deploy with DATABASE_URL secret and PORT=8080
# Rollback: redeploy the previous image digest.
