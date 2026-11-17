output "nlb_dns_name" {
  description = "Aponte o DNS dos seus domínios (CNAME/ALIAS) para cá"
  value       = aws_lb.this.dns_name
}

output "nlb_zone_id" {
  value = aws_lb.this.zone_id
}

output "autoscaling_group" {
  value = aws_autoscaling_group.nodes.name
}

output "security_group_id" {
  value = aws_security_group.nodes.id
}
