#!/usr/bin/env python3
"""Fetch missing KanjiVG strokes and write the writing curriculum JSON.

Adding a later level (N4, more words) is another stage in curriculum.json
plus the glyph strokes. The app loads both files as data.
"""

from __future__ import annotations

import json
import math
import os
import re
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
STROKES = os.path.join(ROOT, "app/src/main/assets/writing/strokes.json")
CURRICULUM = os.path.join(ROOT, "app/src/main/assets/writing/curriculum.json")
KANJIVG = "https://raw.githubusercontent.com/KanjiVG/kanjivg/master/kanji/{code}.svg"

# JLPT N5 starter set (80). Readings are the common ones shown to the learner.
KANJI = [
    ("一", "uno", "one", "イチ / ひと"),
    ("右", "derecha", "right", "ウ / みぎ"),
    ("雨", "lluvia", "rain", "ウ / あめ"),
    ("円", "yen, círculo", "yen, circle", "エン"),
    ("王", "rey", "king", "オウ"),
    ("音", "sonido", "sound", "オン / おと"),
    ("下", "abajo", "below", "カ / した"),
    ("火", "fuego", "fire", "カ / ひ"),
    ("花", "flor", "flower", "カ / はな"),
    ("貝", "concha", "shell", "かい"),
    ("学", "estudio", "study", "ガク / まな"),
    ("気", "ánimo, aire", "spirit", "キ"),
    ("九", "nueve", "nine", "キュウ / ここの"),
    ("休", "descanso", "rest", "キュウ / やす"),
    ("玉", "joya", "jewel", "ギョク / たま"),
    ("金", "oro, dinero", "gold, money", "キン / かね"),
    ("空", "cielo", "sky", "クウ / そら"),
    ("月", "luna, mes", "moon, month", "ゲツ / つき"),
    ("犬", "perro", "dog", "ケン / いぬ"),
    ("見", "ver", "see", "ケン / み"),
    ("五", "cinco", "five", "ゴ / いつ"),
    ("口", "boca", "mouth", "コウ / くち"),
    ("校", "escuela", "school", "コウ"),
    ("左", "izquierda", "left", "サ / ひだり"),
    ("三", "tres", "three", "サン / みっ"),
    ("山", "montaña", "mountain", "サン / やま"),
    ("子", "niño", "child", "シ / こ"),
    ("四", "cuatro", "four", "シ / よん"),
    ("糸", "hilo", "thread", "シ / いと"),
    ("字", "carácter", "character", "ジ"),
    ("耳", "oreja", "ear", "ジ / みみ"),
    ("七", "siete", "seven", "シチ / なな"),
    ("車", "coche", "car", "シャ / くるま"),
    ("手", "mano", "hand", "シュ / て"),
    ("十", "diez", "ten", "ジュウ / とお"),
    ("出", "salir", "exit", "シュツ / で"),
    ("女", "mujer", "woman", "ジョ / おんな"),
    ("小", "pequeño", "small", "ショウ / ちい"),
    ("上", "arriba", "above", "ジョウ / うえ"),
    ("森", "bosque", "forest", "シン / もり"),
    ("人", "persona", "person", "ジン / ひと"),
    ("水", "agua", "water", "スイ / みず"),
    ("正", "correcto", "correct", "セイ / ただ"),
    ("生", "vida", "life", "セイ / い"),
    ("青", "azul", "blue", "セイ / あお"),
    ("夕", "atardecer", "evening", "セキ / ゆう"),
    ("石", "piedra", "stone", "セキ / いし"),
    ("赤", "rojo", "red", "セキ / あか"),
    ("千", "mil", "thousand", "セン"),
    ("川", "río", "river", "セン / かわ"),
    ("先", "delante", "ahead", "セン / さき"),
    ("早", "temprano", "early", "ソウ / はや"),
    ("草", "hierba", "grass", "ソウ / くさ"),
    ("足", "pie", "foot", "ソク / あし"),
    ("村", "pueblo", "village", "ソン / むら"),
    ("大", "grande", "big", "ダイ / おお"),
    ("男", "hombre", "man", "ダン / おとこ"),
    ("竹", "bambú", "bamboo", "チク / たけ"),
    ("中", "dentro", "inside", "チュウ / なか"),
    ("虫", "insecto", "insect", "チュウ / むし"),
    ("町", "ciudad", "town", "チョウ / まち"),
    ("天", "cielo", "heaven", "テン"),
    ("田", "arrozal", "rice field", "デン / た"),
    ("土", "tierra", "soil", "ド / つち"),
    ("二", "dos", "two", "ニ / ふた"),
    ("日", "día, sol", "day, sun", "ニチ / ひ"),
    ("入", "entrar", "enter", "ニュウ / はい"),
    ("年", "año", "year", "ネン / とし"),
    ("白", "blanco", "white", "ハク / しろ"),
    ("八", "ocho", "eight", "ハチ / や"),
    ("百", "cien", "hundred", "ヒャク"),
    ("文", "texto", "text", "ブン"),
    ("木", "árbol", "tree", "モク / き"),
    ("本", "libro, origen", "book, origin", "ホン"),
    ("名", "nombre", "name", "メイ / な"),
    ("目", "ojo", "eye", "モク / め"),
    ("立", "de pie", "stand", "リツ / た"),
    ("力", "fuerza", "power", "リョク / ちから"),
    ("林", "arboleda", "grove", "リン / はやし"),
    ("六", "seis", "six", "ロク / む"),
]


def kata(text: str) -> str:
    out = []
    for ch in text:
        if "\u3041" <= ch <= "\u3096":
            out.append(chr(ord(ch) + 0x60))
        else:
            out.append(ch)
    return "".join(out)


BASIC_ROWS = [
    ("Vocales", "Vowels", "あいうえお"),
    ("Fila ka", "Ka row", "かきくけこ"),
    ("Fila sa", "Sa row", "さしすせそ"),
    ("Fila ta", "Ta row", "たちつてと"),
    ("Fila na", "Na row", "なにぬねの"),
    ("Fila ha", "Ha row", "はひふへほ"),
    ("Fila ma", "Ma row", "まみむめも"),
    ("Fila ya", "Ya row", "やゆよ"),
    ("Fila ra", "Ra row", "らりるれろ"),
    ("Wa, wo, n", "Wa, wo, n", "わをん"),
]

DAKUTEN_ROWS = [
    ("Fila ga", "Ga row", "がぎぐげご"),
    ("Fila za", "Za row", "ざじずぜぞ"),
    ("Fila da", "Da row", "だぢづでど"),
    ("Fila ba", "Ba row", "ばびぶべぼ"),
    ("Fila pa", "Pa row", "ぱぴぷぺぽ"),
]

YOON = [
    ("きゃ・きゅ・きょ", "kya kyu kyo", ["きゃ", "きゅ", "きょ"]),
    ("しゃ・しゅ・しょ", "sha shu sho", ["しゃ", "しゅ", "しょ"]),
    ("ちゃ・ちゅ・ちょ", "cha chu cho", ["ちゃ", "ちゅ", "ちょ"]),
    ("にゃ・にゅ・にょ", "nya nyu nyo", ["にゃ", "にゅ", "にょ"]),
    ("ひゃ・ひゅ・ひょ", "hya hyu hyo", ["ひゃ", "ひゅ", "ひょ"]),
    ("みゃ・みゅ・みょ", "mya myu myo", ["みゃ", "みゅ", "みょ"]),
    ("りゃ・りゅ・りょ", "rya ryu ryo", ["りゃ", "りゅ", "りょ"]),
    ("ぎゃ・ぎゅ・ぎょ", "gya gyu gyo", ["ぎゃ", "ぎゅ", "ぎょ"]),
    ("じゃ・じゅ・じょ", "ja ju jo", ["じゃ", "じゅ", "じょ"]),
    ("びゃ・びゅ・びょ", "bya byu byo", ["びゃ", "びゅ", "びょ"]),
    ("ぴゃ・ぴゅ・ぴょ", "pya pyu pyo", ["ぴゃ", "ぴゅ", "ぴょ"]),
]

WORDS = [
    ("Comida", "Food", [
        ("みず", "agua", "water", "mizu"),
        ("すし", "sushi", "sushi", "sushi"),
        ("にく", "carne", "meat", "niku"),
        ("さかな", "pescado", "fish", "sakana"),
    ]),
    ("Día y lugar", "Day and place", [
        ("あさ", "mañana", "morning", "asa"),
        ("よる", "noche", "night", "yoru"),
        ("いえ", "casa", "house", "ie"),
        ("くに", "país", "country", "kuni"),
    ]),
    ("Seres", "Beings", [
        ("ねこ", "gato", "cat", "neko"),
        ("いぬ", "perro", "dog", "inu"),
        ("ひと", "persona", "person", "hito"),
        ("ともだち", "amigo", "friend", "tomodachi"),
    ]),
    ("Con っ", "With small tsu", [
        ("がっこう", "escuela", "school", "gakkou"),
        ("きっぷ", "billete", "ticket", "kippu"),
        ("ざっし", "revista", "magazine", "zasshi"),
        ("がくせい", "estudiante", "student", "gakusei"),
    ]),
    ("Katakana útil", "Useful katakana", [
        ("コーヒー", "café", "coffee", "koohii"),
        ("パン", "pan", "bread", "pan"),
        ("テレビ", "televisión", "television", "terebi"),
        ("バス", "autobús", "bus", "basu"),
    ]),
]

PHRASES = [
    ("Saludos", "Greetings", [
        ("おはよう", "buenos días", "good morning", "ohayou"),
        ("こんにちは", "hola", "hello", "konnichiwa"),
        ("こんばんは", "buenas noches", "good evening", "konbanwa"),
        ("さようなら", "adiós", "goodbye", "sayounara"),
    ]),
    ("Cortesía", "Courtesy", [
        ("ありがとう", "gracias", "thank you", "arigatou"),
        ("すみません", "perdón", "excuse me", "sumimasen"),
        ("いただきます", "buen provecho", "let's eat", "itadakimasu"),
        ("おやすみ", "que descanses", "good night", "oyasumi"),
    ]),
    ("Salir y volver", "Leaving and returning", [
        ("いってきます", "me voy", "I'm off", "ittekimasu"),
        ("いってらっしゃい", "que te vaya bien", "take care", "itterasshai"),
        ("ただいま", "ya llegué", "I'm home", "tadaima"),
        ("おかえり", "bienvenido", "welcome back", "okaeri"),
    ]),
]


def glyph_item(stage_id, lesson_id, glyph, reading, meaning_es, meaning_en, reveal):
    return {
        "id": f"{lesson_id}-{glyph}",
        "glyphs": list(glyph),
        "reading": reading,
        "meaningEs": meaning_es,
        "meaningEn": meaning_en,
        "reveal": reveal,
    }


def kana_lesson(stage_id, index, title_es, title_en, row, script):
    lesson_id = f"{stage_id}-{index}"
    items = []
    for ch in row:
        shown = ch if script == "hira" else kata(ch)
        items.append(
            glyph_item(stage_id, lesson_id, shown, "", shown, shown, "glyph")
        )
    return {
        "id": lesson_id,
        "titleEs": title_es,
        "titleEn": title_en,
        "items": items,
    }


def stage(stage_id, title_es, title_en, blurb_es, blurb_en, track, premium, lessons):
    return {
        "id": stage_id,
        "titleEs": title_es,
        "titleEn": title_en,
        "blurbEs": blurb_es,
        "blurbEn": blurb_en,
        "track": track,
        "premium": premium,
        "lessons": lessons,
    }


def build_curriculum():
    stages = []
    for script, sid, title_es, title_en, blurb_es, blurb_en, premium, rows in (
        (
            "hira",
            "hira-basic",
            "Hiragana básico",
            "Basic hiragana",
            "Las 46 sílabas, sin dakuten.",
            "The 46 syllables, without dakuten.",
            False,
            BASIC_ROWS,
        ),
        (
            "kata",
            "kata-basic",
            "Katakana básico",
            "Basic katakana",
            "Las mismas sílabas en katakana.",
            "The same syllables in katakana.",
            False,
            BASIC_ROWS,
        ),
        (
            "hira",
            "hira-dakuten",
            "Hiragana con marca",
            "Hiragana with voicing marks",
            "Dakuten y handakuten.",
            "Dakuten and handakuten.",
            True,
            DAKUTEN_ROWS,
        ),
        (
            "kata",
            "kata-dakuten",
            "Katakana con marca",
            "Katakana with voicing marks",
            "Dakuten y handakuten en katakana.",
            "Dakuten and handakuten in katakana.",
            True,
            DAKUTEN_ROWS,
        ),
    ):
        lessons = [
            kana_lesson(sid, i + 1, es, en, row, script)
            for i, (es, en, row) in enumerate(rows)
        ]
        stages.append(stage(sid, title_es, title_en, blurb_es, blurb_en, "kana", premium, lessons))

    youon_lessons = []
    for i, (title, reading, combos) in enumerate(YOON, start=1):
        lesson_id = f"youon-{i}"
        items = []
        for combo in combos:
            items.append(
                glyph_item("youon", lesson_id, combo, "", combo, combo, "glyph")
            )
        youon_lessons.append(
            {"id": lesson_id, "titleEs": title, "titleEn": reading, "items": items}
        )
    kata_youon = []
    for i, (title, reading, combos) in enumerate(YOON, start=1):
        lesson_id = f"youon-kata-{i}"
        items = [
            glyph_item("youon-kata", lesson_id, kata(combo), "", kata(combo), kata(combo), "glyph")
            for combo in combos
        ]
        kata_youon.append(
            {"id": lesson_id, "titleEs": kata(title.split("・")[0]) + "…", "titleEn": reading, "items": items}
        )
    stages.append(
        stage(
            "youon",
            "Combinaciones",
            "Hiragana combinations",
            "きゃ, しゅ y el resto de yōon.",
            "kya, shu and the other yōon.",
            "kana",
            True,
            youon_lessons,
        )
    )
    stages.append(
        stage(
            "youon-kata",
            "Combinaciones katakana",
            "Katakana combinations",
            "Las mismas combinaciones en katakana.",
            "The same combinations in katakana.",
            "kana",
            True,
            kata_youon,
        )
    )

    small_id = "small-1"
    stages.append(
        stage(
            "small-kana",
            "Pequeños y vocal larga",
            "Small kana and long vowels",
            "っ, ゃゅょ y la raya de vocal larga.",
            "Small tsu, small ya/yu/yo, and the long-vowel mark.",
            "kana",
            True,
            [
                {
                    "id": small_id,
                    "titleEs": "っ y ッ",
                    "titleEn": "Small tsu",
                    "items": [
                        glyph_item("small-kana", small_id, "っ", "tsu", "pausa (っ)", "gemination (っ)", "glyph"),
                        glyph_item("small-kana", small_id, "ッ", "tsu", "pausa (ッ)", "gemination (ッ)", "glyph"),
                    ],
                },
                {
                    "id": "small-2",
                    "titleEs": "ゃゅょ",
                    "titleEn": "Small ya yu yo",
                    "items": [
                        glyph_item("small-kana", "small-2", ch, "", ch, ch, "glyph")
                        for ch in "ゃゅょャュョ"
                    ],
                },
                {
                    "id": "small-3",
                    "titleEs": "Vocal larga",
                    "titleEn": "Long vowel",
                    "items": [
                        glyph_item("small-kana", "small-3", "ー", "—", "vocal larga", "long vowel", "glyph"),
                        glyph_item("small-kana", "small-3", "ああ", "aa", "a larga", "long a", "glyph"),
                        glyph_item("small-kana", "small-3", "カー", "kaa", "ka larga", "long ka", "glyph"),
                    ],
                },
            ],
        )
    )

    word_lessons = []
    for i, (title_es, title_en, rows) in enumerate(WORDS, start=1):
        lesson_id = f"vocab-{i}"
        word_lessons.append(
            {
                "id": lesson_id,
                "titleEs": title_es,
                "titleEn": title_en,
                "items": [
                    {
                        "id": f"{lesson_id}-{word}",
                        "glyphs": list(word),
                        "reading": reading,
                        "meaningEs": es,
                        "meaningEn": en,
                        "reveal": "hidden",
                    }
                    for word, es, en, reading in rows
                ],
            }
        )
    stages.append(
        stage(
            "vocab",
            "Vocabulario",
            "Vocabulary",
            "Escribe la palabra a partir del significado.",
            "Write the word from its meaning.",
            "words",
            True,
            word_lessons,
        )
    )

    kanji_lessons = []
    chunk = 5
    for index in range(0, len(KANJI), chunk):
        group = KANJI[index : index + chunk]
        lesson_id = f"n5-{(index // chunk) + 1}"
        kanji_lessons.append(
            {
                "id": lesson_id,
                "titleEs": "".join(ch for ch, *_ in group),
                "titleEn": "".join(ch for ch, *_ in group),
                "items": [
                    {
                        "id": f"{lesson_id}-{ch}",
                        "glyphs": [ch],
                        "reading": reading,
                        "meaningEs": es,
                        "meaningEn": en,
                        "reveal": "glyph",
                    }
                    for ch, es, en, reading in group
                ],
            }
        )
    stages.append(
        stage(
            "kanji-n5",
            "Kanji N5",
            "N5 kanji",
            "Unos 80 kanji de inicio, con lectura y orden de trazos.",
            "About 80 starter kanji, with readings and stroke order.",
            "kanji",
            True,
            kanji_lessons,
        )
    )

    phrase_lessons = []
    for i, (title_es, title_en, rows) in enumerate(PHRASES, start=1):
        lesson_id = f"phrase-{i}"
        phrase_lessons.append(
            {
                "id": lesson_id,
                "titleEs": title_es,
                "titleEn": title_en,
                "items": [
                    {
                        "id": f"{lesson_id}-{phrase}",
                        "glyphs": list(phrase),
                        "reading": reading,
                        "meaningEs": es,
                        "meaningEn": en,
                        "reveal": "hidden",
                    }
                    for phrase, es, en, reading in rows
                ],
            }
        )
    stages.append(
        stage(
            "phrases",
            "Frases",
            "Phrases",
            "Escribe saludos y frases cortas.",
            "Write greetings and short phrases.",
            "phrases",
            True,
            phrase_lessons,
        )
    )
    return {"stages": stages}


TOKEN = re.compile(r"[MmLlHhVvCcSsQqTtAaZz]|[-+]?(?:\d*\.\d+|\d+)(?:[eE][-+]?\d+)?")


def tokenize(d: str):
    for tok in TOKEN.findall(d):
        if len(tok) == 1 and tok.isalpha():
            yield tok, None
        else:
            yield None, float(tok)


def flatten_path(d: str, steps: int = 7):
    tokens = list(tokenize(d))
    i = 0
    cx = cy = 0.0
    sx = sy = 0.0
    last_cmd = ""
    last_ctrl = None
    strokes = []
    current = []

    def push(x, y):
        current.append((x, y))

    def end_stroke():
        nonlocal current
        if len(current) >= 2:
            strokes.append(current)
        current = []

    def cubic(p0, p1, p2, p3):
        pts = []
        for step in range(1, steps + 1):
            t = step / steps
            u = 1 - t
            x = u**3 * p0[0] + 3 * u**2 * t * p1[0] + 3 * u * t**2 * p2[0] + t**3 * p3[0]
            y = u**3 * p0[1] + 3 * u**2 * t * p1[1] + 3 * u * t**2 * p2[1] + t**3 * p3[1]
            pts.append((x, y))
        return pts

    def quad(p0, p1, p2):
        pts = []
        for step in range(1, steps + 1):
            t = step / steps
            u = 1 - t
            x = u**2 * p0[0] + 2 * u * t * p1[0] + t**2 * p2[0]
            y = u**2 * p0[1] + 2 * u * t * p1[1] + t**2 * p2[1]
            pts.append((x, y))
        return pts

    def read_numbers(count):
        nonlocal i
        nums = []
        while len(nums) < count:
            if i >= len(tokens):
                break
            kind, num = tokens[i]
            if kind is not None:
                break
            nums.append(num)
            i += 1
        return nums

    while i < len(tokens):
        kind, num = tokens[i]
        if kind is None:
            kind = last_cmd
        else:
            i += 1
        last_cmd = kind
        rel = kind.islower()
        cmd = kind.upper()
        if cmd == "M":
            nums = read_numbers(2)
            if len(nums) < 2:
                break
            end_stroke()
            x = (cx if rel else 0) + nums[0] if rel else nums[0]
            y = (cy if rel else 0) + nums[1] if rel else nums[1]
            cx, cy = x, y
            sx, sy = x, y
            push(x, y)
            last_cmd = "l" if rel else "L"
            last_ctrl = None
        elif cmd == "L":
            while True:
                nums = read_numbers(2)
                if len(nums) < 2:
                    break
                x = cx + nums[0] if rel else nums[0]
                y = cy + nums[1] if rel else nums[1]
                cx, cy = x, y
                push(x, y)
                last_ctrl = None
        elif cmd == "H":
            nums = read_numbers(1)
            if not nums:
                break
            x = cx + nums[0] if rel else nums[0]
            cx = x
            push(cx, cy)
            last_ctrl = None
        elif cmd == "V":
            nums = read_numbers(1)
            if not nums:
                break
            y = cy + nums[0] if rel else nums[0]
            cy = y
            push(cx, cy)
            last_ctrl = None
        elif cmd == "C":
            while True:
                nums = read_numbers(6)
                if len(nums) < 6:
                    break
                p1 = (cx + nums[0] if rel else nums[0], cy + nums[1] if rel else nums[1])
                p2 = (cx + nums[2] if rel else nums[2], cy + nums[3] if rel else nums[3])
                p3 = (cx + nums[4] if rel else nums[4], cy + nums[5] if rel else nums[5])
                for pt in cubic((cx, cy), p1, p2, p3):
                    push(*pt)
                cx, cy = p3
                last_ctrl = p2
        elif cmd == "S":
            while True:
                nums = read_numbers(4)
                if len(nums) < 4:
                    break
                if last_ctrl is None:
                    p1 = (cx, cy)
                else:
                    p1 = (2 * cx - last_ctrl[0], 2 * cy - last_ctrl[1])
                p2 = (cx + nums[0] if rel else nums[0], cy + nums[1] if rel else nums[1])
                p3 = (cx + nums[2] if rel else nums[2], cy + nums[3] if rel else nums[3])
                for pt in cubic((cx, cy), p1, p2, p3):
                    push(*pt)
                cx, cy = p3
                last_ctrl = p2
        elif cmd == "Q":
            while True:
                nums = read_numbers(4)
                if len(nums) < 4:
                    break
                p1 = (cx + nums[0] if rel else nums[0], cy + nums[1] if rel else nums[1])
                p2 = (cx + nums[2] if rel else nums[2], cy + nums[3] if rel else nums[3])
                for pt in quad((cx, cy), p1, p2):
                    push(*pt)
                cx, cy = p2
                last_ctrl = p1
        elif cmd == "Z":
            push(sx, sy)
            end_stroke()
            cx, cy = sx, sy
            last_ctrl = None
        else:
            break
    end_stroke()
    return strokes


def perp_dist(point, start, end):
    if start == end:
        return math.hypot(point[0] - start[0], point[1] - start[1])
    dx = end[0] - start[0]
    dy = end[1] - start[1]
    return abs(dy * point[0] - dx * point[1] + end[0] * start[1] - end[1] * start[0]) / math.hypot(dx, dy)


def rdp(points, epsilon):
    if len(points) < 3:
        return points
    start, end = points[0], points[-1]
    index, dist = 0, 0.0
    for i in range(1, len(points) - 1):
        d = perp_dist(points[i], start, end)
        if d > dist:
            index, dist = i, d
    if dist > epsilon:
        left = rdp(points[: index + 1], epsilon)
        right = rdp(points[index:], epsilon)
        return left[:-1] + right
    return [start, end]


def round_stroke(points):
    simplified = rdp(points, 1.6)
    out = []
    for x, y in simplified:
        out.append([round(x, 1), round(y, 1)])
    # Drop consecutive duplicates.
    cleaned = [out[0]]
    for pt in out[1:]:
        if pt != cleaned[-1]:
            cleaned.append(pt)
    return cleaned if len(cleaned) >= 2 else out


def fetch(code: str) -> str:
    url = KANJIVG.format(code=code)
    with urllib.request.urlopen(url, timeout=40) as response:
        return response.read().decode("utf-8")


def strokes_from_svg(svg: str):
    # Each KanjiVG stroke is its own path (id ends in -sN). Nested groups close
    # before the next stroke, so a non-greedy group match would keep only stroke 1.
    paths = []
    for tag in re.findall(r"<path\b[^>]*>", svg):
        if not re.search(r'id="kvg:[^"]*-s\d+"', tag):
            continue
        found = re.search(r'\sd="([^"]+)"', tag)
        if found:
            paths.append(found.group(1))
    strokes = []
    for d in paths:
        for stroke in flatten_path(d):
            cleaned = round_stroke(stroke)
            if len(cleaned) >= 2:
                strokes.append(cleaned)
    return strokes


def needed_characters(curriculum):
    chars = set()
    for stage in curriculum["stages"]:
        for lesson in stage["lessons"]:
            for item in lesson["items"]:
                chars.update(item["glyphs"])
    return chars


def main():
    curriculum = build_curriculum()
    os.makedirs(os.path.dirname(CURRICULUM), exist_ok=True)
    with open(CURRICULUM, "w", encoding="utf-8") as handle:
        json.dump(curriculum, handle, ensure_ascii=False, separators=(",", ":"))
        handle.write("\n")
    print("curriculum stages", len(curriculum["stages"]))

    with open(STROKES, encoding="utf-8") as handle:
        stroke_file = json.load(handle)
    glyphs = stroke_file["glyphs"]
    missing = sorted(ch for ch in needed_characters(curriculum) if ch not in glyphs and ch != "ー")
    print("missing", len(missing), "".join(missing))
    failed = []
    for ch in missing:
        code = f"{ord(ch):05x}"
        try:
            svg = fetch(code)
            parsed = strokes_from_svg(svg)
        except Exception as error:  # noqa: BLE001
            failed.append((ch, str(error)))
            continue
        if not parsed:
            failed.append((ch, "no strokes"))
            continue
        glyphs[ch] = parsed
        print("added", ch, len(parsed))
    # Prolonged sound mark is a single horizontal stroke, not a KanjiVG kanji.
    glyphs["ー"] = [[[20.0, 54.0], [90.0, 54.0]]]
    stroke_file["glyphs"] = glyphs
    with open(STROKES, "w", encoding="utf-8") as handle:
        json.dump(stroke_file, handle, ensure_ascii=False, separators=(",", ":"))
        handle.write("\n")
    print("glyphs", len(glyphs), "failed", failed)


if __name__ == "__main__":
    main()
