output "app_url" {
  description = "Open this (it takes a few minutes after the first push)."
  value       = "http://${aws_lb.app.dns_name}"
}

output "ecr_repository_url" {
  description = "Where to push the image."
  value       = aws_ecr_repository.app.repository_url
}

output "region" {
  value = var.region
}

output "cluster" {
  value = aws_ecs_cluster.main.name
}

output "service" {
  value = aws_ecs_service.app.name
}
