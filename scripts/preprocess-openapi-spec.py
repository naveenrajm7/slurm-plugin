#!/usr/bin/env python3
"""
Preprocesses the SLURM OpenAPI specification to remove version prefixes.

This script removes version prefixes (e.g., 'v0.0.42_' or 'v0042') from:
- Schema names in components/schemas
- All $ref references
- Operation IDs in paths

The processed spec allows OpenAPI Generator to create version-independent
Java classes, making it easier to upgrade to newer SLURM versions.

Usage:
    python scripts/preprocess-openapi-spec.py \
        src/main/resources/openapi/slurm-v0.0.42.json \
        target/generated-resources/openapi/slurm-processed.json
"""

import json
import re
import sys
from pathlib import Path


def remove_version_prefix(name):
    """
    Remove version prefix from a schema or operation name.
    
    Examples:
        v0.0.42_openapi_ping_array_resp -> openapi_ping_array_resp
        slurm_v0042_get_ping -> slurm_get_ping
        slurmdb_v0042_get_config -> slurmdb_get_config
    """
    # Remove v0.0.XX_ prefix (schema names)
    name = re.sub(r'^v0\.0\.\d+_', '', name)
    
    # Remove v00XX from operation IDs (e.g., slurm_v0042_get_ping -> slurm_get_ping)
    name = re.sub(r'_v\d{4}_', '_', name)
    
    return name


def process_ref(ref):
    """Process a $ref string to remove version prefix from the referenced schema."""
    if not ref or not isinstance(ref, str):
        return ref
    
    # Extract the schema name from #/components/schemas/schema_name
    if ref.startswith('#/components/schemas/'):
        schema_name = ref.split('/')[-1]
        new_schema_name = remove_version_prefix(schema_name)
        return f'#/components/schemas/{new_schema_name}'
    
    return ref


def process_object(obj):
    """Recursively process an object to update all $ref references."""
    if isinstance(obj, dict):
        result = {}
        for key, value in obj.items():
            if key == '$ref':
                result[key] = process_ref(value)
            else:
                result[key] = process_object(value)
        return result
    elif isinstance(obj, list):
        return [process_object(item) for item in obj]
    else:
        return obj


def preprocess_spec(input_file, output_file):
    """
    Main preprocessing function.
    
    Args:
        input_file: Path to input OpenAPI spec file
        output_file: Path to output processed spec file
    """
    print(f"Reading OpenAPI spec from: {input_file}")
    
    # Read the input spec
    with open(input_file, 'r', encoding='utf-8') as f:
        spec = json.load(f)
    
    # Process schema names in components/schemas
    if 'components' in spec and 'schemas' in spec['components']:
        old_schemas = spec['components']['schemas']
        new_schemas = {}
        
        print(f"Processing {len(old_schemas)} schema definitions...")
        
        for schema_name, schema_def in old_schemas.items():
            new_schema_name = remove_version_prefix(schema_name)
            new_schemas[new_schema_name] = schema_def
            
            if new_schema_name != schema_name:
                print(f"  {schema_name} -> {new_schema_name}")
        
        spec['components']['schemas'] = new_schemas
    
    # Process operation IDs in paths
    if 'paths' in spec:
        print(f"\nProcessing {len(spec['paths'])} path definitions...")
        
        for path, path_item in spec['paths'].items():
            for method, operation in path_item.items():
                if isinstance(operation, dict) and 'operationId' in operation:
                    old_op_id = operation['operationId']
                    new_op_id = remove_version_prefix(old_op_id)
                    operation['operationId'] = new_op_id
                    
                    if new_op_id != old_op_id:
                        print(f"  {path} [{method}]: {old_op_id} -> {new_op_id}")
    
    # Update all $ref references throughout the spec
    print("\nUpdating all $ref references...")
    spec = process_object(spec)
    
    # Ensure output directory exists
    output_path = Path(output_file)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    
    # Write the processed spec
    print(f"\nWriting processed spec to: {output_file}")
    with open(output_file, 'w', encoding='utf-8') as f:
        json.dump(spec, f, indent=2)
    
    print("[SUCCESS] OpenAPI spec preprocessing complete!")


def main():
    if len(sys.argv) != 3:
        print("Usage: python preprocess-openapi-spec.py <input-spec> <output-spec>")
        print("\nExample:")
        print("  python scripts/preprocess-openapi-spec.py \\")
        print("      src/main/resources/openapi/slurm-v0.0.42.json \\")
        print("      target/generated-resources/openapi/slurm-processed.json")
        sys.exit(1)
    
    input_file = sys.argv[1]
    output_file = sys.argv[2]
    
    try:
        preprocess_spec(input_file, output_file)
    except FileNotFoundError:
        print(f"Error: Input file not found: {input_file}")
        sys.exit(1)
    except json.JSONDecodeError as e:
        print(f"Error: Invalid JSON in input file: {e}")
        sys.exit(1)
    except Exception as e:
        print(f"Error: {e}")
        sys.exit(1)


if __name__ == '__main__':
    main()
