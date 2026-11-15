# Topologia AWS recomendada: NLB (multi-AZ, gerenciado pela AWS) -> Auto Scaling Group de HAProxy.
# Não há VIP/keepalived: a alta disponibilidade da "porta de entrada" é do NLB; o HAPM gerencia
# listeners, regras e certificados de todos os nós (o agente se registra sozinho no manager).

data "aws_ssm_parameter" "ubuntu" {
  name = "/aws/service/canonical/ubuntu/server/24.04/stable/current/amd64/hvm/ebs-gp3/ami-id"
}

data "aws_vpc" "this" {
  id = var.vpc_id
}

data "aws_region" "current" {}

data "aws_caller_identity" "current" {}

locals {
  ami_id = var.ami_id != "" ? var.ami_id : data.aws_ssm_parameter.ubuntu.value
  tags   = merge({ "hapm:cluster" = var.cluster_label, "managed-by" = "terraform" }, var.tags)
  s3_arns = [for u in compact([var.agent_jar_s3_uri, var.agent_scripts_s3_uri, var.otel_config_s3_uri]) :
    "arn:aws:s3:::${replace(u, "s3://", "")}${endswith(u, "/") ? "*" : ""}"]
}

# ------------------------------------------------------------------ rede
resource "aws_security_group" "nodes" {
  name        = "${var.name}-haproxy"
  description = "Nós HAProxy gerenciados pelo HAPM"
  vpc_id      = var.vpc_id
  tags        = local.tags
}

resource "aws_vpc_security_group_ingress_rule" "clients" {
  for_each          = { for p in setproduct(var.listener_ports, var.client_cidrs) : "${p[0]}-${p[1]}" => p }
  security_group_id = aws_security_group.nodes.id
  ip_protocol       = "tcp"
  from_port         = each.value[0]
  to_port           = each.value[0]
  cidr_ipv4         = each.value[1]
  description       = "clientes via NLB (IP de origem preservado)"
}

resource "aws_vpc_security_group_ingress_rule" "health" {
  security_group_id = aws_security_group.nodes.id
  ip_protocol       = "tcp"
  from_port         = var.stats_port
  to_port           = var.stats_port
  cidr_ipv4         = data.aws_vpc.this.cidr_block
  description       = "health-check do NLB (/healthz)"
}

resource "aws_vpc_security_group_egress_rule" "all" {
  security_group_id = aws_security_group.nodes.id
  ip_protocol       = "-1"
  cidr_ipv4         = "0.0.0.0/0"
}

# ------------------------------------------------------------------ IAM (SSM Session Manager, token, artefatos)
resource "aws_iam_role" "nodes" {
  name = "${var.name}-haproxy"
  assume_role_policy = jsonencode({
    Version   = "2012-10-17"
    Statement = [{ Effect = "Allow", Principal = { Service = "ec2.amazonaws.com" }, Action = "sts:AssumeRole" }]
  })
  tags = local.tags
}

resource "aws_iam_role_policy_attachment" "ssm_core" {
  role       = aws_iam_role.nodes.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

resource "aws_iam_role_policy" "nodes" {
  name = "hapm-node"
  role = aws_iam_role.nodes.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect   = "Allow"
        Action   = ["ssm:GetParameter"]
        Resource = "arn:aws:ssm:${data.aws_region.current.name}:${data.aws_caller_identity.current.account_id}:parameter/${trimprefix(var.join_token_ssm_parameter, "/")}"
      },
      { Effect = "Allow", Action = ["s3:GetObject"], Resource = local.s3_arns },
      { Effect = "Allow", Action = ["s3:ListBucket"], Resource = "arn:aws:s3:::${split("/", replace(var.agent_scripts_s3_uri, "s3://", ""))[0]}" },
    ]
  })
}

resource "aws_iam_instance_profile" "nodes" {
  name = "${var.name}-haproxy"
  role = aws_iam_role.nodes.name
}

# ------------------------------------------------------------------ instâncias
resource "aws_launch_template" "nodes" {
  name_prefix   = "${var.name}-haproxy-"
  image_id      = local.ami_id
  instance_type = var.instance_type
  iam_instance_profile { arn = aws_iam_instance_profile.nodes.arn }
  vpc_security_group_ids = [aws_security_group.nodes.id]
  metadata_options {
    http_tokens                 = "required" # IMDSv2
    http_put_response_hop_limit = 1
  }
  user_data = base64encode(templatefile("${path.module}/user-data.sh.tftpl", {
    manager_url           = var.manager_url
    join_token_parameter  = var.join_token_ssm_parameter
    agent_jar_s3_uri      = var.agent_jar_s3_uri
    agent_scripts_s3_uri  = var.agent_scripts_s3_uri
    otel_gateway_endpoint = var.otel_gateway_endpoint
    otelcol_version       = var.otelcol_version
    otel_config_s3_uri    = var.otel_config_s3_uri
    cluster_label         = var.cluster_label
    region                = data.aws_region.current.name
  }))
  tag_specifications {
    resource_type = "instance"
    tags          = merge(local.tags, { Name = "${var.name}-haproxy" })
  }
}

resource "aws_autoscaling_group" "nodes" {
  name                      = "${var.name}-haproxy"
  vpc_zone_identifier       = var.instance_subnet_ids
  min_size                  = var.min_size
  max_size                  = var.max_size
  desired_capacity          = var.desired_capacity
  health_check_type         = "ELB"
  health_check_grace_period = 300
  target_group_arns         = [for tg in aws_lb_target_group.port : tg.arn]

  launch_template {
    id      = aws_launch_template.nodes.id
    version = "$Latest"
  }

  # troca de AMI/user-data sem derrubar o serviço
  instance_refresh {
    strategy = "Rolling"
    preferences {
      min_healthy_percentage = 50
      instance_warmup        = 240
    }
  }

  dynamic "tag" {
    for_each = local.tags
    content {
      key                 = tag.key
      value               = tag.value
      propagate_at_launch = true
    }
  }
}

# ------------------------------------------------------------------ NLB
resource "aws_lb" "this" {
  name                             = "${var.name}-nlb"
  load_balancer_type               = "network"
  internal                         = var.internal
  subnets                          = var.nlb_subnet_ids
  enable_cross_zone_load_balancing = true
  tags                             = local.tags
}

resource "aws_lb_target_group" "port" {
  for_each             = toset([for p in var.listener_ports : tostring(p)])
  name                   = "${var.name}-tg-${each.key}"
  port                   = tonumber(each.key)
  protocol               = "TCP"
  vpc_id                 = var.vpc_id
  target_type            = "instance" # preserva o IP do cliente
  deregistration_delay   = 30
  connection_termination = false

  health_check {
    protocol            = "HTTP"
    port                = tostring(var.stats_port)
    path                = "/healthz"
    interval            = 10
    healthy_threshold   = 2
    unhealthy_threshold = 2
  }
  tags = local.tags
}

resource "aws_lb_listener" "port" {
  for_each          = aws_lb_target_group.port
  load_balancer_arn = aws_lb.this.arn
  port              = tonumber(each.key)
  protocol          = "TCP"
  default_action {
    type             = "forward"
    target_group_arn = each.value.arn
  }
}
