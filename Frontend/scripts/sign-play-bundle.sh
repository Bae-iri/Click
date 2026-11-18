#!/usr/bin/env bash
# Sign the prepared bundle on the Mac holding the EXISTING Play upload key.
set -euo pipefail
if [[ $# -lt 1 || $# -gt 3 ]]; then
  echo "Usage: bash sign-play-bundle.sh input-unsigned.aab [existing-upload-key.jks] [alias]" >&2
  exit 2
fi
bundle_input=$1
upload_key=${2:-"$HOME/click_upload.jks"}
upload_alias=${3:-click-upload}
bundle_output="${bundle_input%.aab}-signed.aab"
[[ -f "$bundle_input" ]] || { echo "Input AAB does not exist" >&2; exit 2; }
[[ -f "$upload_key" ]] || { echo "Existing upload keystore does not exist" >&2; exit 2; }
[[ ! -e "$bundle_output" ]] || { echo "Output already exists; choose another input filename" >&2; exit 2; }
command -v jarsigner >/dev/null || { echo "Install JDK 17 and make jarsigner available" >&2; exit 2; }
echo "Confirm the bundle version code is higher than every version already uploaded to Play."
echo "Enter the existing keystore password at the jarsigner prompt. No password is stored by this script."
jarsigner -keystore "$upload_key" -signedjar "$bundle_output" "$bundle_input" "$upload_alias"
jarsigner -verify "$bundle_output"
existing_bundle="$HOME/Desktop/프로젝트/Click/Frontend/app/release/app-release.aab"
if [[ -f "$existing_bundle" ]]; then
  certificate_fingerprint() {
    keytool -J-Duser.language=en -printcert -jarfile "$1" | awk '/SHA256:/ {print $2; exit}'
  }
  previous_fingerprint=$(certificate_fingerprint "$existing_bundle")
  signed_fingerprint=$(certificate_fingerprint "$bundle_output")
  if [[ -z "$previous_fingerprint" || "$previous_fingerprint" != "$signed_fingerprint" ]]; then
    echo "Upload certificate differs from the existing signed AAB. Do not upload this bundle." >&2
    exit 3
  fi
  echo "Upload certificate matches the existing signed AAB."
fi
echo "Signed bundle: $bundle_output"
echo "Compare its upload certificate with the existing Play upload certificate before uploading."
