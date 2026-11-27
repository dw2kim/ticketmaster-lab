# Deploy to AWS with Terraform (without Elasticsearch)

The same app and the same Docker image as [Render](deploy.md), on the services bigger
companies use: ECS Fargate, RDS PostgreSQL, ElastiCache Redis and a load balancer. The whole
setup is code in `infra/aws/` (Terraform). Why: [ADR-0012](decisions/0012-aws-with-terraform.md).

> **Not run end to end yet.** The Terraform files pass `terraform validate`, but they have not
> been applied to a real AWS account from this repo. The expected outputs below come from how
> Terraform and the AWS CLI behave, not from a recorded run. If a step prints something
> else, stop and paste it.

## The picture

```
 internet --HTTP :80--> ALB (load balancer, 2 zones)          public subnets
                          |
                          v  :8080 (only from the ALB)
                     ECS Fargate task: the app image (ARM)    public subnets
                     |                       |
         :5432 only from app       :6379 only from app
                     v                       v
             RDS PostgreSQL 17        ElastiCache Redis 7      private subnets (no internet)
             password in Secrets Manager
```

## AWS words, mapped to Render

| AWS | Render | What it is |
| --- | --- | --- |
| VPC + subnets | (hidden) | Your private network. Public subnets reach the internet; private ones do not. |
| Security group | `ipAllowList` | A firewall per piece: who may connect on which port. |
| ECR | (built for you) | Where your Docker images are stored. |
| ECS on Fargate | web service | Runs your container; no servers to manage. |
| ALB | (built in) | Load balancer: the public address, health checks, spreads traffic. |
| RDS | Postgres | Managed Postgres: backups, patches. |
| ElastiCache | Key Value | Managed Redis. |
| Secrets Manager | env var from database | Keeps the DB password; ECS hands it to the app at start. |
| CloudWatch Logs | Logs tab | The app's output. |
| Terraform | `render.yaml` | The whole setup as code. |

## Cost (us-west-2, rough; check the AWS pricing pages)

| Piece | Size | About / month |
| --- | --- | --- |
| Load balancer (ALB) | 1 | $18 |
| Fargate task | 0.5 vCPU, 1 GB, ARM | $15 |
| RDS PostgreSQL | db.t4g.micro, 20 GB | $14 |
| ElastiCache Redis | cache.t4g.micro | $12 |
| Public IPv4 addresses | 3 (ALB x2, task x1) | $11 |
| Logs, ECR, secret | small | $1-2 |
| **Total** | | **about $70 / month, about $0.10 / hour** |

AWS bills by the hour. Try it for an afternoon, then `terraform destroy`: a few dollars.
New accounts may get free-tier credits; check your account's Billing page.

## Before you start (one time)

1. **An AWS account**, and a **budget alert**: Console -> Billing -> Budgets -> Create
   budget -> "Monthly cost budget", e.g. $20, with your email. Do this first.
2. **Do not use the root user.** Console -> IAM Identity Center (or IAM) -> create a user for
   yourself with `AdministratorAccess`, and use that.
3. **Tools on your Mac** (Terminal 2, any folder):

```bash
brew install awscli
brew tap hashicorp/tap
brew install hashicorp/tap/terraform
```

```bash
aws configure
```

   It asks for an access key, secret key, region (`us-west-2`) and output (`json`).
   Check it worked:

```bash
aws sts get-caller-identity
```

   Expected: JSON with your `Account` number and your user's `Arn` (not `root`).

## How to run it

All commands: Terminal 2, folder `ticketmaster-lab`, branch `main` (after this PR is merged),
Docker Desktop running.

### 1. Create the image repository first

The app can't start before its image exists, so create ECR alone first:

```bash
terraform -chdir=infra/aws init
terraform -chdir=infra/aws apply -target=aws_ecr_repository.app
```

Expected: `init` ends with `Terraform has been successfully initialized!`. `apply` shows
`Plan: 1 to add`, asks `Enter a value:`, you type `yes`, then
`Apply complete! Resources: 1 added` (plus a warning about `-target`; that's expected here).

### 2. Build and push the image

```bash
REPO=$(terraform -chdir=infra/aws output -raw ecr_repository_url)
aws ecr get-login-password --region us-west-2 | docker login --username AWS --password-stdin ${REPO%%/*}
docker build --platform linux/arm64 -t $REPO:latest .
docker push $REPO:latest
```

Expected: `Login Succeeded`, the build (a few minutes the first time), then the push ends
with `latest: digest: sha256:...`. On an Intel Mac the ARM build is slower (emulated) but works.

### 3. Create everything else

```bash
terraform -chdir=infra/aws apply
```

Expected: `Plan: 28 to add, 0 to change, 0 to destroy.` Type `yes`. It takes about 10-15
minutes (RDS is the slow one). It ends with:

```
Apply complete! Resources: 28 added, 0 changed, 0 destroyed.
Outputs:
app_url = "http://ticketmaster-....us-west-2.elb.amazonaws.com"
...
```

### 4. Check it

Give the app 2-3 minutes to start, then:

```bash
APP=$(terraform -chdir=infra/aws output -raw app_url)
curl -s $APP/actuator/health
curl -s $APP/events/1
curl -s "$APP/events/search?keyword=jazz"
```

Expected: `"status":"UP"`, the Lab Concert with 20 seats, Quiet Jazz Night. A `503` at first
means the task is still starting. Watch its logs (Ctrl+C to stop):

```bash
aws logs tail /ecs/ticketmaster --follow
```

Look for `Successfully applied 7 migrations` and `Tomcat started on port 8080`.

### 5. Deploy a change

After the change is merged to `main` (CI green):

```bash
git pull
docker build --platform linux/arm64 -t $REPO:latest .
docker push $REPO:latest
aws ecs update-service --cluster ticketmaster --service ticketmaster --force-new-deployment > /dev/null
```

ECS starts a new task, waits for `/actuator/health`, then stops the old one: no downtime. If
the new one never gets healthy, the circuit breaker rolls back to the old one.

### 6. Delete everything (stop the bill)

```bash
terraform -chdir=infra/aws destroy
```

Expected: `Plan: 0 to add, 0 to change, 29 to destroy.`, type `yes`, about 10 minutes,
then `Destroy complete! Resources: 29 destroyed.` The data is gone too (lab setting).

## Security

- Same as Render: **no login** on the app; the URL is a demo only. No load tests against AWS.
- Postgres and Redis are in private subnets and their security groups only accept the app.
- The DB password is created by AWS (`manage_master_user_password`), kept in Secrets
  Manager, and given to the app at start. It is not in git or in the Terraform state.
- The app's security group only accepts traffic from the load balancer.
- **Keep `terraform.tfstate` safe and out of git** (`.gitignore` has it). It lists every
  resource; teams keep it in a locked S3 bucket (see below).
- **HTTP only.** HTTPS needs a domain name; see below.

## What a real company adds (not set up here)

| Add | Why | Cost |
| --- | --- | --- |
| HTTPS: Route 53 domain + ACM certificate on the ALB | encrypt traffic | ~$1-15 / year for a domain; cert free |
| Remote state: S3 bucket with locking | the team shares one state; no two applies at once | cents |
| App in private subnets + NAT gateway | the app has no public IP at all | ~$32 / month |
| CI deploys: GitHub Actions with OIDC (no stored AWS keys) | merge = deploy, like Render | free |
| RDS Multi-AZ, ElastiCache replica | survive a zone going down | doubles those lines |
| Auto scaling on the ECS service | more copies at a flash sale | pay per task |
| WAF + rate limits | block abuse in front of the ALB | ~$5+ / month |
| Elasticsearch: Amazon OpenSearch Service | steps 9-10 in the cloud | ~$25+ / month (smallest) |

## What to understand

- Render and AWS run the **same image with the same env vars**; only who wires the network,
  the load balancer and the secrets differs. On AWS you wire it, so you see each piece.
- Security groups chain the layers: internet -> ALB -> app -> database/Redis. Each layer only
  accepts the one in front of it, so a leaked DB address is still unreachable.
- Terraform makes the setup repeatable and removable: `apply` builds 29 resources the same
  way every time, `destroy` removes all of them, and the bill stops.
