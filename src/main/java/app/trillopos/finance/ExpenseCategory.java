package app.trillopos.finance;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import app.trillopos.shared.persistence.TenantEntity;

/** Rent, electricity, transport (spec §7). Not the product category. */
@Entity
@Table(name = "expense_category")
public class ExpenseCategory extends TenantEntity {

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    protected ExpenseCategory() {
    }

    public ExpenseCategory(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }
}
