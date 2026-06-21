import atexit
import json
import sys
import os

# SCons-plugin introspection shim
# This script is intended to be injected into site_scons/site_init.py

class SConsIntrospect:
    def __init__(self):
        self.targets = []
        self.options = []
        self.variables = {}
        self._patch_scons()

    def _patch_scons(self):
        try:
            from SCons.Environment import Environment
            from SCons.Variables import Variables
            
            # Patch Environment.__init__ to capture construction environments
            original_init = Environment.__init__
            def patched_init(env_self, *args, **kwargs):
                original_init(env_self, *args, **kwargs)
                self._wrap_env(env_self)
            Environment.__init__ = patched_init

            # Patch Variables.Add to capture options
            original_add = Variables.Add
            def patched_add(vars_self, *args, **kwargs):
                original_add(vars_self, *args, **kwargs)
                # Capture variables definition
                # Variables.Add(key, help='', default=None, validator=None, converter=None)
                # or Variables.Add(VariableObject)
                if len(args) > 0:
                    key = args[0]
                    if isinstance(key, str):
                        help_text = kwargs.get('help', '')
                        default = kwargs.get('default', '')
                        # Try to infer type
                        self.options.append({
                            "key": key,
                            "help": help_text,
                            "default": str(default),
                            "type": "string" # Default to string, improved later if possible
                        })
            Variables.Add = patched_add
            
        except Exception:
            pass

    def _wrap_env(self, env):
        # Wrap builder methods to capture targets
        for builder_name in env['BUILDERS']:
            original_builder = env['BUILDERS'][builder_name]
            def make_wrapper(name, original):
                def wrapper(*args, **kwargs):
                    result = original(*args, **kwargs)
                    try:
                        self._capture_target(env, name, args, kwargs, result)
                    except Exception:
                        pass
                    return result
                return wrapper
            env['BUILDERS'][builder_name] = make_wrapper(builder_name, original_builder)

    def _capture_target(self, env, type_name, args, kwargs, result):
        # result is usually a list of Nodes
        if not result:
            return
            
        name = str(result[0])
        sources = []
        if len(args) > 1:
            srcs = args[1]
            if isinstance(srcs, list):
                sources = [str(s) for s in srcs]
            else:
                sources = [str(srcs)]
        elif 'source' in kwargs:
            srcs = kwargs['source']
            if isinstance(srcs, list):
                sources = [str(s) for s in srcs]
            else:
                sources = [str(srcs)]

        # Extract environment variables
        env_data = {}
        for key in ['CC', 'CXX', 'SHCC', 'SHCXX', 'CCFLAGS', 'CPPPATH', 'CPPDEFINES', 'LINKFLAGS']:
            if key in env:
                val = env[key]
                if isinstance(val, list):
                    env_data[key] = [str(v) for v in val]
                else:
                    env_data[key] = str(val)

        self.targets.append({
            "name": name,
            "type": type_name,
            "sources": sources,
            "env": env_data
        })

    def dump(self):
        output = {
            "schema_version": 1,
            "targets": self.targets,
            "options": self.options
        }
        json_str = json.dumps(output, indent=2)
        # Write to stdout with a marker for easy extraction if needed
        print("---SCONS_INTROSPECT_BEGIN---")
        print(json_str)
        print("---SCONS_INTROSPECT_END---")
        
        # Also write to a file in the current directory (usually project root)
        try:
            with open(".scons_introspect.json", "w") as f:
                f.write(json_str)
        except Exception:
            pass

_introspector = SConsIntrospect()
atexit.register(_introspector.dump)
