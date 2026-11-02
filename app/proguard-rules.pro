# Conservative metadata preservation for libraries that use annotations,
# generic signatures, and nested-class metadata at runtime.
# R8 also consumes each dependency's bundled consumer rules.
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# Do not add broad package-wide keep rules here: they would prevent R8
# from removing and optimizing unused application/library code.
