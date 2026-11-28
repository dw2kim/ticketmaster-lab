# PostgreSQL (RDS): the record of every sale. Redis (ElastiCache): holds, cache, waiting room.
# Both live in the private subnets; only the app's security group can connect.

resource "aws_db_subnet_group" "main" {
  name       = var.name
  subnet_ids = aws_subnet.private[*].id
}

resource "aws_db_instance" "postgres" {
  identifier        = var.name
  engine            = "postgres"
  engine_version    = "17"
  instance_class    = "db.t4g.micro" # 2 vCPU (burstable), 1 GB: enough for the lab
  allocated_storage = 20
  storage_type      = "gp3"
  storage_encrypted = true

  db_name  = "ticketmaster"
  username = "ticketmaster"
  # AWS creates the password and keeps it in Secrets Manager. It is never in our files
  # or in the Terraform state; the app gets it at start (see app.tf, "secrets").
  manage_master_user_password = true

  db_subnet_group_name   = aws_db_subnet_group.main.name
  vpc_security_group_ids = [aws_security_group.db.id]
  publicly_accessible    = false

  backup_retention_period = 7 # daily backups, kept 7 days
  # Lab settings so `terraform destroy` really removes it. In a real company: a final
  # snapshot and deletion_protection = true.
  skip_final_snapshot = true
  deletion_protection = false
}

resource "aws_elasticache_subnet_group" "main" {
  name       = var.name
  subnet_ids = aws_subnet.private[*].id
}

# The default Redis policy may evict keys that have a TTL when memory is full; holds and
# admitted keys all have one. Never evict: a full Redis should say "no", not forget a hold.
resource "aws_elasticache_parameter_group" "redis" {
  name   = "${var.name}-redis7"
  family = "redis7"
  parameter {
    name  = "maxmemory-policy"
    value = "noeviction"
  }
}

resource "aws_elasticache_cluster" "redis" {
  cluster_id           = var.name
  engine               = "redis"
  engine_version       = "7.1"
  node_type            = "cache.t4g.micro"
  num_cache_nodes      = 1
  port                 = 6379
  parameter_group_name = aws_elasticache_parameter_group.redis.name
  subnet_group_name    = aws_elasticache_subnet_group.main.name
  security_group_ids   = [aws_security_group.redis.id]
}
