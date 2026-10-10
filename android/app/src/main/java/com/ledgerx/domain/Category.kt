package com.ledgerx.domain

/**
 * Expense/income category with an emoji icon.
 *
 * Eight defaults are seeded on first login. Users can create
 * additional custom categories later.
 */
data class ExpenseCategory(
    val id: String,
    val userId: String,
    val name: String,
    val icon: String,
    val isDefault: Boolean = false,
    val sortOrder: Int = 0
)

/** The eight built-in categories seeded for every new user. */
object DefaultCategories {
    data class Seed(val id: String, val name: String, val icon: String, val sortOrder: Int)

    val all = listOf(
        Seed("eating",        "Eating",        "🍔", 0),
        Seed("payment",       "Payment",       "💡", 1),
        Seed("transport",     "Transport",     "🚌", 2),
        Seed("shopping",      "Shopping",      "🛍️", 3),
        Seed("education",     "Education",     "📚", 4),
        Seed("entertainment", "Entertainment", "🎮", 5),
        Seed("health",        "Health",        "💊", 6),
        Seed("other",         "Others",        "📦", 7),
    )
}
