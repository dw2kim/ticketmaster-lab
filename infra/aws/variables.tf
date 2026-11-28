variable "region" {
  description = "AWS region to create everything in."
  type        = string
  default     = "us-east-2"
}

variable "name" {
  description = "Prefix for every resource name."
  type        = string
  default     = "ticketmaster"
}

variable "image_tag" {
  description = "Which image tag in ECR the app runs."
  type        = string
  default     = "latest"
}

variable "app_count" {
  description = "How many copies of the app run behind the load balancer."
  type        = number
  default     = 1
}
