#!/bin/bash

# HeliBoard Package Rename Script
# Renames package from helium314.keyboard to com.macboard.keyboard
# This script is safe and creates backups before making changes

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Configuration
OLD_PACKAGE="helium314.keyboard"
NEW_PACKAGE="com.macboard.keyboard"
OLD_NAMESPACE="helium314.keyboard.latin"
NEW_NAMESPACE="com.macboard.keyboard.latin"
OLD_PATH="app/src/main/java/helium314/keyboard"
NEW_PATH="app/src/main/java/com/macboard/keyboard"
BACKUP_DIR="package_rename_backup_$(date +%s)"

# Helper functions
log_info() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

log_success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# Check if we're in the right directory
if [ ! -f "app/build.gradle.kts" ]; then
    log_error "app/build.gradle.kts not found. Please run this script from the project root."
    exit 1
fi

log_info "Starting package rename: $OLD_PACKAGE → $NEW_PACKAGE"
log_info "Creating backup in: $BACKUP_DIR"

# Create backup
mkdir -p "$BACKUP_DIR"
cp -r app "$BACKUP_DIR/app_backup"
cp app/src/main/AndroidManifest.xml "$BACKUP_DIR/AndroidManifest.xml.backup"

log_success "Backup created"

# Step 1: Update build.gradle.kts
log_info "Updating build.gradle.kts..."
sed -i.bak "s/applicationId = \"$OLD_PACKAGE\"/applicationId = \"$NEW_PACKAGE\"/g" app/build.gradle.kts
sed -i.bak "s/namespace = \"$OLD_NAMESPACE\"/namespace = \"$NEW_NAMESPACE\"/g" app/build.gradle.kts
rm -f app/build.gradle.kts.bak
log_success "build.gradle.kts updated"

# Step 2: Update AndroidManifest.xml
log_info "Updating AndroidManifest.xml..."
sed -i.bak "s/$OLD_PACKAGE/$NEW_PACKAGE/g" app/src/main/AndroidManifest.xml
sed -i.bak "s/$OLD_NAMESPACE/$NEW_NAMESPACE/g" app/src/main/AndroidManifest.xml
rm -f app/src/main/AndroidManifest.xml.bak
log_success "AndroidManifest.xml updated"

# Step 3: Update all Java and Kotlin source files
log_info "Updating package declarations in .java and .kt files..."
find app/src/main/java -type f \( -name "*.java" -o -name "*.kt" \) -exec sed -i.bak "s/package $OLD_PACKAGE/package $NEW_PACKAGE/g" {} \;
find app/src/main/java -type f \( -name "*.java" -o -name "*.kt" \) -exec sed -i.bak "s/import $OLD_PACKAGE/import $NEW_PACKAGE/g" {} \;
find app/src/main/java -type f \( -name "*.java" -o -name "*.kt" \) -exec sed -i.bak "s/\"$OLD_PACKAGE/\"$NEW_PACKAGE/g" {} \;
find app/src/main/java -type f \( -name "*.java" -o -name "*.kt" \) -exec sed -i.bak "s/'$OLD_PACKAGE/'$NEW_PACKAGE/g" {} \;

# Also handle the namespace references in Kotlin files
find app/src/main/java -type f -name "*.kt" -exec sed -i.bak "s/$OLD_NAMESPACE/$NEW_NAMESPACE/g" {} \;

# Clean up backup files from sed
find app/src/main/java -type f -name "*.bak" -delete

log_success "Package declarations updated in source files"

# Step 4: Refactor directory structure
log_info "Refactoring directory structure..."
log_info "  Creating new directory: $NEW_PATH"
mkdir -p "$NEW_PATH"

# Move all files from old structure to new structure
if [ -d "$OLD_PATH" ]; then
    log_info "  Moving files from old structure to new structure..."
    find "$OLD_PATH" -type f -exec bash -c 'src="$1"; dst="${src//$OLD_PATH/$NEW_PATH}"; mkdir -p "$(dirname "$dst")"; mv "$src" "$dst"' _ {} \;
    
    # Remove empty directories
    find "app/src/main/java/helium314" -type d -empty -delete 2>/dev/null || true
    
    log_success "Directory structure refactored"
fi

# Step 5: Update XML resources
log_info "Checking for package references in XML resources..."
find app/src/main/res -type f -name "*.xml" -exec sed -i.bak "s/$OLD_PACKAGE/$NEW_PACKAGE/g" {} \;
find app/src/main/res -type f -name "*.xml" -exec sed -i.bak "s/$OLD_NAMESPACE/$NEW_NAMESPACE/g" {} \;
find app/src/main/res -type f -name "*.xml.bak" -delete

log_success "XML resources updated"

# Step 6: Check for other potential occurrences
log_info "Searching for any remaining references to old package name..."
remaining=$(grep -r "$OLD_PACKAGE" app/src/main --include="*.kt" --include="*.java" --include="*.xml" 2>/dev/null | wc -l)
if [ $remaining -gt 0 ]; then
    log_warn "Found $remaining potential remaining references to $OLD_PACKAGE:"
    grep -r "$OLD_PACKAGE" app/src/main --include="*.kt" --include="*.java" --include="*.xml" 2>/dev/null || true
    log_warn "Please review these manually."
else
    log_success "No remaining references to old package name found!"
fi

# Final summary
echo ""
echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}Package rename completed successfully!${NC}"
echo -e "${GREEN}========================================${NC}"
echo ""
echo -e "Summary of changes:"
echo -e "  • ${BLUE}Package name${NC}: $OLD_PACKAGE → $NEW_PACKAGE"
echo -e "  • ${BLUE}Namespace${NC}: $OLD_NAMESPACE → $NEW_NAMESPACE"
echo -e "  • ${BLUE}Directory${NC}: $OLD_PATH → $NEW_PATH"
echo -e "  • ${BLUE}Files updated${NC}: build.gradle.kts, AndroidManifest.xml, all .java/.kt files, XML resources"
echo ""
echo -e "${YELLOW}Next steps:${NC}"
echo -e "  1. ${BLUE}Verify changes${NC}: Review the modified files"
echo -e "  2. ${BLUE}Clean build${NC}: Run 'gradle clean build' or use Android Studio's Clean/Rebuild"
echo -e "  3. ${BLUE}Test${NC}: Build and run the app to ensure everything works"
echo ""
echo -e "${YELLOW}Backup location:${NC} $BACKUP_DIR"
echo -e "If something goes wrong, you can restore from: $BACKUP_DIR/app_backup"
echo ""
