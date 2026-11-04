import unittest
from grouping import group_questions


def line(text,y,x=30,right=900,score=.99):
    return {'text':text,'box':[x,y,right,y+25],'score':score}


class GroupingTest(unittest.TestCase):
    def test_indented_repeated_option_number_is_kept_in_the_parent_question(self):
        result=group_questions([line('3. 选择下面的条件。',50,right=450),line('3) 苹果是草莓的4倍。',150,x=550,right=850),line('4. 根据条件解答。',600),line('【附加题】',800)],1000,1000)
        self.assertEqual([q['number'] for q in result],['3','4','附加题'])
        self.assertIn('3) 苹果',result[0]['text'])
    def test_numbered_questions_keep_answers_and_space(self):
        result=group_questions([line('1. 小明有12颗糖，又得到8颗。',100),line('一共有多少颗？',150),line('2. 计算下面各题。',500)],1000,1000)
        self.assertEqual(len(result),2)
        self.assertIn('一共有多少颗',result[0]['text'])
        self.assertLessEqual(result[0]['regions'][0]['y']+result[0]['regions'][0]['height'],.5)

    def test_reading_material_is_preserved_for_each_selected_subquestion(self):
        result=group_questions([line('一、阅读短文',50),line('小兔走进森林，遇见了朋友。',100),line('他们一起寻找回家的路。',150),line('1. 小兔遇见了谁？',400),line('2. 请写出你的感受。',600)],1000,1000)
        self.assertEqual(len(result),2)
        self.assertTrue(all(len(q['regions'])==2 for q in result))
        self.assertIn('森林',result[1]['text'])

    def test_children_keep_parent_question(self):
        result=group_questions([line('1. 选择正确的答案。',100),line('(1) 下面哪一个是三角形？',200),line('(2) 5+3等于多少？',400),line('2. 计算题。',700)],1000,1000)
        self.assertEqual([q['number'] for q in result],['1','2'])
        self.assertIn('选择正确',result[0]['text'])
        self.assertIn('(1)',result[0]['text'])
        self.assertIn('(2)',result[0]['text'])
        self.assertEqual(len(result[0]['regions']),1)

    def test_reading_image_is_shared_even_when_fast_scan_only_reads_the_heading(self):
        result=group_questions([line('一、阅读短文',50),line('1. 回答问题。',400),line('2. 写出感受。',700)],1000,1000)
        self.assertEqual(len(result),2)
        self.assertTrue(all(len(q['regions'])==2 for q in result))
        self.assertEqual(result[0]['regions'][0],result[1]['regions'][0])
        self.assertLess(result[0]['regions'][0]['y'],.1)
        self.assertGreater(result[0]['regions'][0]['height'],.3)

    def test_number_touching_quantity_and_bonus_heading_end_parent_question(self):
        result=group_questions([line('3.(1) 根据图示选择条件。',50),line('（2）列式解答。',300),
            line('4.3位家长带6名儿童，怎样买票最划算？',550),line('【附加题】',800),line('说明理由。',850)],1000,1000,
            [{'label':'paragraph_title','score':.8,'coordinate':[25,545,950,580]}])
        self.assertEqual([q['number'] for q in result],['3','4','附加题'])
        self.assertNotIn('家长',result[0]['text'])
        self.assertNotIn('附加题',result[1]['text'])
        self.assertTrue(result[1]['warnings'])
        self.assertLessEqual(result[0]['regions'][0]['y']+result[0]['regions'][0]['height'],.55)

    def test_decimal_inside_a_question_does_not_become_the_next_question(self):
        result=group_questions([line('3. 请比较下面的长度。',50),line('4.3米与4.5米',200),line('4. 完成计算。',500)],1000,1000)
        self.assertEqual([q['number'] for q in result],['3','4'])
        self.assertIn('4.3米',result[0]['text'])

    def test_bonus_subquestions_stay_inside_bonus_question(self):
        result=group_questions([line('3. 计算。',50),line('附加题：',400),line('1. 写出方法。',500),line('2. 说明理由。',700)],1000,1000)
        self.assertEqual([q['number'] for q in result],['3','附加题'])
        self.assertIn('2. 说明理由',result[1]['text'])

    def test_number_restart_does_not_merge_sections(self):
        result=group_questions([line('7. 解决问题。',70),line('第三单元综合练习',300),line('1. 计算12+8。',400)],1000,1000)
        self.assertEqual([q['number'] for q in result],['7','1'])

    def test_two_columns_have_separate_regions(self):
        result=group_questions([line('1. 左栏题目。',100,right=420),line('2. 左栏第二题。',500,right=420),line('3. 右栏题目。',110,x=550,right=950)],1000,1000)
        self.assertEqual(len(result),3)
        self.assertTrue(all(q['regions'][0]['width']<.5 for q in result))

    def test_decimal_is_not_question_number_and_unknown_page_is_manual(self):
        result=group_questions([line('1.5吨等于多少千克',100)],1000,1000)
        self.assertEqual(result[0]['group'],'手工框题')
        self.assertTrue(result[0]['warnings'])

    def test_empty_page_does_not_fabricate_ocr_text(self):
        result=group_questions([],1000,1000)
        self.assertEqual(result[0]['text'],'')
        self.assertTrue(result[0]['warnings'])

    def test_layout_excludes_page_number_and_warns_when_frame_cuts_diagram(self):
        result=group_questions([line('1. 第一题',100),line('2. 第二题',500),line('12. 页码',940)],1000,1000,
            [{'label':'page_number','score':.9,'coordinate':[0,930,1000,980]},
             {'label':'image','score':.9,'coordinate':[100,450,400,550]}])
        self.assertEqual(len(result),2)
        self.assertTrue(all(any('配图或公式' in warning for warning in q['warnings']) for q in result))

    def test_wide_question_text_does_not_get_split_into_columns(self):
        result=group_questions([line('1. 这是一道横跨整页的题。',100),line('2. 右侧编号内容',160,x=550,right=950)],1000,1000)
        self.assertTrue(all(q['regions'][0]['width']>.9 for q in result))


if __name__=='__main__':unittest.main()
