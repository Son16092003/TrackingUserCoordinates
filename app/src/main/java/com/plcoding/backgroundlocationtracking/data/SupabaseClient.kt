package com.plcoding.backgroundlocationtracking.data

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest

object SupabaseClientProvider {

    private const val SUPABASE_URL = "https://piggzhqyiewotlhzjhcm.supabase.co"
    private const val SUPABASE_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InBpZ2d6aHF5aWV3b3RsaHpqaGNtIiwicm9sZSI6ImFub24iLCJpYXQiOjE3NTgxMjQ1MzIsImV4cCI6MjA3MzcwMDUzMn0.Yyq_gJY4J2qhPbIZPe_zXOFvQswt7-GnVD8MOJ-asW8"

    val client: SupabaseClient by lazy {
        createSupabaseClient(
            supabaseUrl = SUPABASE_URL,
            supabaseKey = SUPABASE_KEY
        ) {
            install(Postgrest) // 🔥 Bắt buộc để dùng from() và CRUD
        }
    }
}
