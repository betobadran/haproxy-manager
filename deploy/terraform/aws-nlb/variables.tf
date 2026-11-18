variable "name" {
  description = "Prefixo dos recursos (ex.: hapm-prod)"
  type        = string
}

variable "vpc_id" {
  type = string
}

variable "instance_subnet_ids" {
  description = "Subnets (privadas, em AZs diferentes) onde rodam os nós HAProxy"
  type        = list(string)
}

variable "nlb_subnet_ids" {
  description = "Subnets do NLB (públicas se internet-facing). Uma por AZ."
  type        = list(string)
}

variable "internal" {
  description = "NLB interno (true) ou internet-facing (false)"
  type        = bool
  default     = false
}

variable "listener_ports" {
  description = "Portas TCP expostas no NLB e repassadas aos listeners do HAProxy (configurados no HAProxy Manager)"
  type        = list(number)
  default     = [80, 443]
}

variable "client_cidrs" {
  description = "Origem permitida dos clientes. O NLB preserva o IP do cliente, então o SG das instâncias precisa liberá-los."
  type        = list(string)
  default     = ["0.0.0.0/0"]
}

variable "instance_type" {
  type    = string
  default = "c7i.large"
}

variable "ami_id" {
  description = "AMI (vazio = Ubuntu 24.04 mais recente da Canonical)"
  type        = string
  default     = ""
}

variable "min_size" {
  type    = number
  default = 2
}

variable "max_size" {
  type    = number
  default = 6
}

variable "desired_capacity" {
  type    = number
  default = 2
}

variable "manager_url" {
  description = "URL do HAProxy Manager acessível pelas instâncias (ex.: https://hapm.interno.exemplo.com)"
  type        = string
}

variable "join_token_ssm_parameter" {
  description = "Nome do parâmetro SSM (SecureString) com o token de join do cluster (Visão geral > Adicionar nós)"
  type        = string
}

variable "bundle_s3_uri" {
  description = "s3://bucket/caminho/hapm-bundle-<versão>.tar.gz (gerado por deploy/bash/make-bundle.sh)"
  type        = string
}

variable "otel_gateway_endpoint" {
  description = "host:porta OTLP gRPC do OTel Collector gateway (vazio = não instala o collector)"
  type        = string
  default     = ""
}

variable "otelcol_version" {
  type    = string
  default = "0.114.0"
}

variable "cluster_label" {
  description = "Valor do label hapm_cluster nas métricas"
  type        = string
  default     = "aws"
}

variable "stats_port" {
  description = "Porta do frontend interno hapm_stats (health-check do NLB em /healthz)"
  type        = number
  default     = 8405
}

variable "tags" {
  type    = map(string)
  default = {}
}

variable "lifecycle_hook_timeout" {
  description = "Segundos que a instância espera em Terminating:Wait para o agente avisar o manager (e o NLB drenar) antes de terminar"
  type        = number
  default     = 120
}
