# AssMatroskaExtractor accesses these Media3 fields by reflection; preserve their names in consuming apps.
-keepclassmembers class androidx.media3.extractor.mkv.MatroskaExtractor { *** extractorOutput; *** subtitleSample; }
