import unittest
from safebank_agent.graph import focus_products

class WealthFocusTest(unittest.TestCase):
    def test_three_turn_object_continuity(self):
        first=focus_products([],'请解释固收90天持有和固收180天持有的关键差异、主要限制和不确定项（产品编号：DEMO-WM-02、DEMO-WM-03）。')
        self.assertEqual(first,['DEMO-WM-02','DEMO-WM-03'])
        second=focus_products(first,'这两个哪个更灵活')
        self.assertEqual(second,['DEMO-WM-02','DEMO-WM-03'])
        third=focus_products(second,'90天后会自动到账吗')
        self.assertEqual(third,['DEMO-WM-02','DEMO-WM-03'])
    def test_switch_replaces_previous_objects(self):
        self.assertEqual(focus_products(['DEMO-WM-02','DEMO-WM-03'],'换成 DEMO-WM-01 看看'),['DEMO-WM-01'])
        self.assertEqual(focus_products(['DEMO-WM-01','DEMO-WM-02','DEMO-WM-03'],None),['DEMO-WM-01','DEMO-WM-02'])
    def test_unrelated_question_does_not_invent_or_drop_objects(self):
        self.assertEqual(focus_products(['DEMO-WM-02','DEMO-WM-03'],'分析2026-09账单'),['DEMO-WM-02','DEMO-WM-03'])
        self.assertEqual(focus_products(None,'你好'),[])
