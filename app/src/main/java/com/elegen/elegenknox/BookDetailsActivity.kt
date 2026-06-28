package com.elegen.elegencashbook

import android.os.Bundle
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class BookDetailsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_book_details)

        val name = intent.getStringExtra("book_name") ?: "Book"
        val meta = intent.getStringExtra("book_meta") ?: ""
        val balance = intent.getStringExtra("book_balance") ?: "₦0.00"

        findViewById<TextView>(R.id.detail_name).text = name
        findViewById<TextView>(R.id.detail_meta).text = meta
        findViewById<TextView>(R.id.detail_balance).text = balance
        findViewById<ImageButton>(R.id.back_button).setOnClickListener { finish() }
    }
}
