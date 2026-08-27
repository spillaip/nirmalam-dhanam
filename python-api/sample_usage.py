from nirmalam_dhanam_api import (
    NirmalamDataManager,
    TransactionFilter,
)


def run() -> None:
    manager = NirmalamDataManager.from_interchange("nirmalam-dhanam-interchange.json")
    salary_items = manager.filter_transactions(
        TransactionFilter(category="Salary", direction="CREDIT"),
    )
    print("Summary:", manager.summary())
    print("Salary items:", len(salary_items))


if __name__ == "__main__":
    run()
