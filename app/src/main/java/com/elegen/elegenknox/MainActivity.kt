package com.elegen.elegencashbook

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.textfield.TextInputEditText
import androidx.activity.enableEdgeToEdge

class MainActivity : AppCompatActivity() {

    private val businesses = listOf(
        Business("Studio House", "Owner", 3, true),
        Business("Northwind Co", "Manager", 2, false),
        Business("Blue Oak", "Viewer", 1, false)
    )

    private val books = mutableListOf(
        Book("June Expenses", "Updated today", "₦24,500"),
        Book("Petty Cash Book", "Created 2 days ago", "₦8,200"),
        Book("Project Book", "Created 1 week ago", "₦42,100")
    )

    private lateinit var booksRecyclerView: RecyclerView
    private lateinit var booksAdapter: BookAdapter
    private lateinit var businessTitle: TextView
    private var selectedBusinessIndex = 0

    private fun showBookDetails(book: Book) {
        val intent = Intent(this, BookDetailsActivity::class.java).apply {
            putExtra("book_name", book.name)
            putExtra("book_meta", book.meta)
            putExtra("book_balance", book.balance)
        }
        startActivity(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        val rootView = findViewById<View>(R.id.main)
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }

        businessTitle = findViewById(R.id.business_title)
        //val inviteButton = findViewById<ImageButton>(R.id.invite_button)
        val addBookButton = findViewById<MaterialButton>(R.id.add_book_button)
        val businessSelector = findViewById<View>(R.id.business_selector)
        booksRecyclerView = findViewById(R.id.books_list)

        booksAdapter = BookAdapter(books)
        booksRecyclerView.layoutManager = LinearLayoutManager(this)
        booksRecyclerView.adapter = booksAdapter

        updateBusinessSelection()

        businessSelector.setOnClickListener { showBusinessSheet() }
        //inviteButton.setOnClickListener { /* no-op for visual parity */ }
        addBookButton.setOnClickListener { showAddBookSheet() }
    }

    private fun updateBusinessSelection() {
        val business = businesses[selectedBusinessIndex]
        businessTitle.text = business.name
    }

    private fun showBusinessSheet() {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_business, null)
        val businessList = view.findViewById<RecyclerView>(R.id.business_list)
        val closeButton = view.findViewById<ImageButton>(R.id.close_business_sheet)
        val addBusinessButton = view.findViewById<MaterialButton>(R.id.add_business_button)

        businessList.layoutManager = LinearLayoutManager(this)
        businessList.adapter = BusinessAdapter(businesses) { index ->
            selectedBusinessIndex = index
            updateBusinessSelection()
            dialog.dismiss()
        }

        closeButton.setOnClickListener { dialog.dismiss() }
        addBusinessButton.setOnClickListener {
            dialog.dismiss()
            startActivity(Intent(this, AddBusinessActivity::class.java))
        }

        dialog.setContentView(view)
        dialog.show()
    }

    private fun showAddBookSheet() {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_add_book, null)
        val bookNameInput = view.findViewById<TextInputEditText>(R.id.book_name_input)
        val addBookButton = view.findViewById<MaterialButton>(R.id.add_book_button)
        val closeButton = view.findViewById<ImageButton>(R.id.close_book_sheet)
        val suggestionChips = listOf(
            view.findViewById<Chip>(R.id.chip_june),
            view.findViewById<Chip>(R.id.chip_petty),
            view.findViewById<Chip>(R.id.chip_project),
            view.findViewById<Chip>(R.id.chip_client)
        )

        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                addBookButton.isEnabled = !s.isNullOrBlank()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        }
        bookNameInput.addTextChangedListener(watcher)

        suggestionChips.forEach { chip ->
            chip.setOnClickListener {
                bookNameInput.setText(chip.text)
                bookNameInput.setSelection(bookNameInput.text?.length ?: 0)
            }
        }

        closeButton.setOnClickListener { dialog.dismiss() }
        addBookButton.setOnClickListener {
            val name = bookNameInput.text?.toString()?.trim().orEmpty()
            if (name.isNotBlank()) {
                books.add(0, Book(name, "Added just now", "₦0.00"))
                booksAdapter.notifyItemInserted(0)
                booksRecyclerView.scrollToPosition(0)
                dialog.dismiss()
            }
        }

        dialog.setContentView(view)
        dialog.show()
    }

    private data class Business(
        val name: String,
        val role: String,
        val bookCount: Int,
        val isSelected: Boolean
    )

    private data class Book(
        val name: String,
        val meta: String,
        val balance: String
    )

    private inner class BusinessAdapter(
        private val items: List<Business>,
        private val onSelected: (Int) -> Unit
    ) : RecyclerView.Adapter<BusinessAdapter.BusinessViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BusinessViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_business, parent, false)
            return BusinessViewHolder(view)
        }

        override fun onBindViewHolder(holder: BusinessViewHolder, position: Int) {
            val business = items[position]
            holder.name.text = business.name
            holder.role.text = business.role
            holder.count.text = "${business.bookCount} books"
            holder.initial.text = business.name.firstOrNull()?.uppercaseChar()?.toString().orEmpty()
            holder.selected.visibility = if (position == selectedBusinessIndex) View.VISIBLE else View.GONE
        }

        override fun getItemCount() = items.size

        private inner class BusinessViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(R.id.business_name)
            val role: TextView = view.findViewById(R.id.business_role)
            val count: TextView = view.findViewById(R.id.business_count)
            val initial: TextView = view.findViewById(R.id.business_initial)
            val selected: ImageView = view.findViewById(R.id.selected_indicator)

            init {
                view.setOnClickListener {
                    val position = adapterPosition
                    if (position != RecyclerView.NO_POSITION) {
                        onSelected(position)
                    }
                }
            }
        }
    }

    private inner class BookAdapter(
        private val items: MutableList<Book>
    ) : RecyclerView.Adapter<BookAdapter.BookViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BookViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_book, parent, false)
            return BookViewHolder(view)
        }

        override fun onBindViewHolder(holder: BookViewHolder, position: Int) {
            val book = items[position]
            holder.name.text = book.name
            holder.meta.text = book.meta
            holder.balance.text = book.balance
            holder.icon.text = book.name.firstOrNull()?.uppercaseChar()?.toString().orEmpty()
            holder.itemView.setOnClickListener { showBookDetails(book) }
        }

        override fun getItemCount() = items.size

        private inner class BookViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(R.id.book_name)
            val meta: TextView = view.findViewById(R.id.book_meta)
            val balance: TextView = view.findViewById(R.id.book_balance)
            val icon: TextView = view.findViewById(R.id.book_icon)
        }
    }
}