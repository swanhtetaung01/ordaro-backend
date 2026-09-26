package app.trillopos.catalog;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import app.trillopos.shared.persistence.TenantEntity;

/** Self-referencing for sub-categories; the UI keeps it to two levels. */
@Entity
@Table(name = "category")
public class Category extends TenantEntity {

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "parent_id")
    private UUID parentId;

    protected Category() {
    }

    public Category(String name, UUID parentId) {
        this.name = name;
        this.parentId = parentId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public UUID getParentId() {
        return parentId;
    }

    public void setParentId(UUID parentId) {
        this.parentId = parentId;
    }
}
