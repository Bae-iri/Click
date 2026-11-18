terraform {
  required_version = ">= 1.6"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
  }
}

variable "region" {
  type    = string
  default = "ap-southeast-1"
}

variable "bucket_name" {
  type        = string
  description = "Globally unique NEW dedicated private backup bucket name."
}

provider "aws" {
  region = var.region
}

data "aws_caller_identity" "current" {}

locals {
  prefix = "click/postgres/production/"
}

resource "aws_s3_bucket" "backup" {
  bucket        = var.bucket_name
  force_destroy = false
  tags          = { Application = "Click", Purpose = "private-production-db-backup" }
  lifecycle {
    prevent_destroy = true
  }
}

# Deliberately never enable versioning: no recoverable old versions after expiry.
resource "aws_s3_bucket_public_access_block" "backup" {
  bucket                  = aws_s3_bucket.backup.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_ownership_controls" "backup" {
  bucket = aws_s3_bucket.backup.id
  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "backup" {
  bucket = aws_s3_bucket.backup.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "backup" {
  bucket = aws_s3_bucket.backup.id
  rule {
    id     = "expire-click-backups-before-365-days"
    status = "Enabled"
    filter {
      prefix = local.prefix
    }
    expiration {
      days = 364
    }
    abort_incomplete_multipart_upload {
      days_after_initiation = 1
    }
  }
}

data "aws_iam_policy_document" "bucket" {
  statement {
    sid       = "DenyInsecureTransport"
    effect    = "Deny"
    actions   = ["s3:*"]
    resources = [aws_s3_bucket.backup.arn, "${aws_s3_bucket.backup.arn}/*"]
    principals {
      type        = "*"
      identifiers = ["*"]
    }
    condition {
      test     = "Bool"
      variable = "aws:SecureTransport"
      values   = ["false"]
    }
  }
}

resource "aws_s3_bucket_policy" "backup" {
  bucket = aws_s3_bucket.backup.id
  policy = data.aws_iam_policy_document.bucket.json
}

resource "aws_iam_user" "backup" {
  name = "click-db-backup"
}

# No access key or secret is generated into Terraform state. Create the access
# key securely, then put it only in the Render secret environment group.
data "aws_iam_policy_document" "backup_job" {
  statement {
    actions = ["s3:GetBucketPublicAccessBlock", "s3:GetBucketPolicyStatus",
      "s3:GetBucketOwnershipControls", "s3:GetBucketVersioning",
    "s3:GetEncryptionConfiguration", "s3:GetLifecycleConfiguration"]
    resources = [aws_s3_bucket.backup.arn]
  }
  statement {
    actions   = ["s3:ListBucket"]
    resources = [aws_s3_bucket.backup.arn]
    condition {
      test     = "StringLike"
      variable = "s3:prefix"
      values   = ["${local.prefix}*"]
    }
  }
  statement {
    actions   = ["s3:PutObject", "s3:GetObject", "s3:DeleteObject", "s3:AbortMultipartUpload"]
    resources = ["${aws_s3_bucket.backup.arn}/${local.prefix}*"]
  }
}

resource "aws_iam_user_policy" "backup" {
  user   = aws_iam_user.backup.name
  policy = data.aws_iam_policy_document.backup_job.json
}

output "backup_uri" {
  value = "s3://${aws_s3_bucket.backup.id}/${local.prefix}"
}

output "expected_bucket_owner" {
  value = data.aws_caller_identity.current.account_id
}

output "backup_iam_user" {
  value = aws_iam_user.backup.name
}
