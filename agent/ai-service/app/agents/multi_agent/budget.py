import asyncio
import time

class BudgetExceeded(ValueError):
    pass

class Budget:
    def __init__(self,seconds):
        self.deadline=time.monotonic()+seconds
        self.total=0
        self.lock=asyncio.Lock()
    def remaining(self):
        return max(.001,self.deadline-time.monotonic())
    async def claim(self):
        async with self.lock:
            if self.total>=12 or time.monotonic()>=self.deadline:
                raise BudgetExceeded("REQUEST_BUDGET_EXHAUSTED")
            self.total+=1
