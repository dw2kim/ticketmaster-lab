# The lab on AWS: the same Docker image as Render, with AWS's managed services.
# Guide: docs/deploy-aws.md. Remove everything (and stop the bill): terraform destroy
#
#   internet -> ALB (load balancer, port 80) -> ECS Fargate task (the app, port 8080)
#                                                  |-> RDS PostgreSQL   (private subnets)
#                                                  '-> ElastiCache Redis (private subnets)

terraform {
  required_version = ">= 1.9"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
  }
}

provider "aws" {
  region = var.region
  # Every resource gets these tags, so you can find (and pay for) the lab in the console.
  default_tags {
    tags = {
      Project   = var.name
      ManagedBy = "terraform"
    }
  }
}

data "aws_availability_zones" "available" {
  state = "available"
}

locals {
  # Two availability zones (data centers): the load balancer and RDS need subnets in two.
  azs = slice(data.aws_availability_zones.available.names, 0, 2)
}
