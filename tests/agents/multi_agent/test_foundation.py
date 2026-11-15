import unittest
from pydantic import ValidationError
from app.agents.multi_agent.schemas import AgentTask,TaskPlan
from app.agents.multi_agent.budget import Budget,BudgetExceeded

class FoundationTests(unittest.IsolatedAsyncioTestCase):
    def test_limits(self):
        with self.assertRaises(ValidationError):
            AgentTask(task_id="1",agent="shell",objective="bad")
        with self.assertRaises(ValidationError):
            AgentTask(task_id="1",agent="monitoring",objective="GPU",tool_budget=5)
        self.assertEqual(TaskPlan(intent="general").tasks,[])
    async def test_concurrent_global_budget_is_atomic(self):
        import asyncio
        budget=Budget(20)
        results=await asyncio.gather(*(budget.claim() for _ in range(40)),return_exceptions=True)
        self.assertEqual(budget.total,12)
        self.assertEqual(sum(isinstance(r,BudgetExceeded) for r in results),28)

    async def test_global_budget(self):
        budget=Budget(20)
        for _ in range(12): await budget.claim()
        with self.assertRaises(BudgetExceeded): await budget.claim()
