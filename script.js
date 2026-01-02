const GRID_SIZE = 8;
let mapData = []; 
let selectedType = 'MY_TILE';
let currentUser = null;
let isSignupMode = false; // 기본값은 로그인 모드

// 1. 서버 주소 설정 (Render 서버 주소)
const SERVER_URL = "https://autocardbattle.onrender.com";

// 2. Firebase 초기화 및 설정
async function setupFirebase() {
    try {
        const response = await fetch(`${SERVER_URL}/api/config/firebase`, {
            headers: { 'Origin': window.location.origin }
        });
        const config = await response.json();
        firebase.initializeApp(config);

        // ✅ 추가: 주사위 마스터 데이터를 미리 로드하여 렌더링 오류 방지
        const diceRes = await fetch(`${SERVER_URL}/api/dice/list`);
        allDice = await diceRes.json();

        // 인증 상태 확인
        firebase.auth().onAuthStateChanged((user) => {
            if (user) {
                // 이미 로그인된 상태라면 서버 로그인 진행
                handleServerLogin(user);
            }
        });
    } catch (error) {
        console.error("Firebase 로딩 실패:", error);
    }
}

// 3. [에러 해결 핵심] 폼 전환 로직 (toggleAuthMode)
// HTML의 onclick="toggleAuthMode(event)"와 이름이 정확히 일치해야 합니다.
function toggleAuthMode(e) {
    if (e) e.preventDefault();
    isSignupMode = !isSignupMode;
    
    const title = document.getElementById('auth-title');
    const btn = document.getElementById('main-auth-btn');
    const nickGroup = document.getElementById('nickname-group');
    const switchText = document.getElementById('auth-switch-text');

    if (isSignupMode) {
        title.innerText = "회원가입";
        btn.innerText = "회원가입 하기";
        nickGroup.style.display = "block";
        switchText.innerHTML = '이미 계정이 있나요? <a href="#" onclick="toggleAuthMode(event)">로그인</a>';
    } else {
        title.innerText = "로그인";
        btn.innerText = "로그인";
        nickGroup.style.display = "none";
        switchText.innerHTML = '계정이 없으신가요? <a href="#" onclick="toggleAuthMode(event)">회원가입</a>';
    }
}

// 4. 통합 인증 실행 (로그인/회원가입 버튼 클릭 시)
async function handleAuthAction() {
    const email = document.getElementById('user-email').value;
    const password = document.getElementById('user-password').value;
    const nickname = document.getElementById('user-nickname').value;

    if (!email || !password) return alert("이메일과 비밀번호를 입력하세요.");

    try {
        if (isSignupMode) {
            // 회원가입 모드
            if (!nickname) return alert("닉네임을 입력하세요!");
            const result = await firebase.auth().createUserWithEmailAndPassword(email, password);
            
            // 서버 DB에 닉네임과 UID 등록
            const response = await fetch(`${SERVER_URL}/api/user/login`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ uid: result.user.uid, username: nickname })
            });

            if (response.ok) {
                alert("회원가입 성공! 로그인 해주세요.");
                isSignupMode = false;
                toggleAuthMode(); // 로그인 모드로 전환
            }
        } else {
            // 로그인 모드
            const result = await firebase.auth().signInWithEmailAndPassword(email, password);
            handleServerLogin(result.user);
        }
    } catch (error) {
        alert("오류: " + error.message);
    }
}

// 서버 세션 로그인 처리
async function handleServerLogin(firebaseUser) {
    try {
        const response = await fetch(`${SERVER_URL}/api/user/login`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ uid: firebaseUser.uid })
        });

        if (response.ok) {
            currentUser = await response.json();
            showEditor();
        }
    } catch (error) {
        console.error("서비 로그인 실패:", error);
    }
}

function showEditor() {
    document.getElementById('auth-form').style.display = 'none';
    document.getElementById('editor-section').style.display = 'block';
    const userDisplay = document.getElementById('user-display');
    if (userDisplay) userDisplay.innerText = `${currentUser.username}님 접속 중`;
    initMap();
}

function handleLogout() {
    firebase.auth().signOut().then(() => {
        window.location.reload();
    });
}

// 5. 맵 에디터 관련 로직 (기존 코드 유지)
function initMap() {
    const gridElement = document.getElementById('map-grid');
    if (!gridElement) return;

    // 1. [중요] 기존 UI와 데이터를 완전히 초기화합니다.
    gridElement.innerHTML = ''; 
    mapData = []; 

    for (let y = 0; y < GRID_SIZE; y++) {
        for (let x = 0; x < GRID_SIZE; x++) {
            // UI 생성
            const tile = document.createElement('div');
            tile.classList.add('tile');
            tile.id = `tile-${x}-${y}`;
            
            // 왼쪽 4칸만 클릭 가능 (대칭 시스템 유지)
            if (x < 4) {
                tile.onclick = () => handleTileClick(x, y);
            } else {
                tile.classList.add('symmetric-zone');
            }
            
            gridElement.appendChild(tile);

            // 2. [중요] 데이터 배열에 객체 추가
            mapData.push({ x, y, tileType: 'EMPTY' });
        }
    }
}

function updateTile(x, y, type) {
    // 3. [핵심] find를 통해 정확한 객체를 찾아 업데이트
    const tileObj = mapData.find(t => t.x === x && t.y === y);
    if (tileObj) {
        tileObj.tileType = type;
        const el = document.getElementById(`tile-${x}-${y}`);
        if (el) {
            // 클래스 초기화 후 재설정
            el.className = `tile ${type} ${x >= 4 ? 'symmetric-zone' : ''}`;
            // 텍스트 표시
            el.innerText = type === 'EMPTY' ? '' : (type === 'MY_TILE' ? '내 타일' : (type === 'ENEMY_TILE' ? '적 타일' : '벽'));
        }
    }
}

function handleTileClick(x, y) {
    const type = selectedType;
    updateTile(x, y, type);
    const symX = 7 - x;
    let symType = type;
    if (type === 'MY_TILE') symType = 'ENEMY_TILE';
    else if (type === 'ENEMY_TILE') symType = 'MY_TILE';
    updateTile(symX, y, symType);
}

function selectType(type, e) {
    selectedType = type;
    document.querySelectorAll('.tool-btn').forEach(b => b.classList.remove('active'));
    e.target.classList.add('active');
}

function saveMap() {
    // 1. mapData 배열에서 tileType만 추출
    const tilesArray = mapData.map(t => t.tileType);
    
    // 2. 빈 공간('EMPTY')이 하나라도 있는지 단순 체크
    if (tilesArray.includes('EMPTY')) {
        alert("⚠️ 아직 비어있는 칸이 있습니다. 모든 칸을 채워주세요!");
        return;
    }

    // 3. 모든 칸이 채워졌다면 쉼표로 합치기
    const fullMapString = tilesArray.join(",");
    
    // 4. 서버 전송
    fetch(`${SERVER_URL}/api/map/save`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
            mapData: fullMapString,
            creatorUid: currentUser.firebaseUid
        })
    }).then(res => {
        if (res.ok) {
            alert("✅ 전장이 성공적으로 저장되었습니다!");
            navTo('home'); // 저장 후 홈 화면으로 이동
        } else {
            alert("❌ 저장 실패: 서버 오류가 발생했습니다.");
        }
    }).catch(err => {
        console.error("통신 오류:", err);
    });
}

// 전투시 맵 그려주는 함
function loadMapToGrid(fullMapString, isBattle = true) {
    if (!fullMapString) return;
    
    const tiles = fullMapString.split(",");
    const gridElement = document.getElementById('map-grid');
    gridElement.innerHTML = ''; 
    mapData = []; 

    // ✅ 핵심: 내가 '두 번째 유저'라면 맵의 진영을 반전시킵니다.
    // (이 로직을 위해 서버에서 내가 몇 번째 유저인지 정보를 주거나, 
    // 방 생성 시 배정된 역할을 확인해야 합니다. 여기서는 간단히 로직만 설명합니다.)
    
    tiles.forEach((type, i) => {
        const x = i % GRID_SIZE;
        const y = Math.floor(i / GRID_SIZE);
        
        let adjustedType = type;
        
        // 만약 내가 '적군' 입장으로 매칭되었다면 타입을 뒤바꿉니다.
        // (isSecondPlayer 변수는 매칭 성공 시 서버에서 받아온 정보를 바탕으로 설정)
        if (isBattle && isSecondPlayer) {
            if (type === 'MY_TILE') adjustedType = 'ENEMY_TILE';
            else if (type === 'ENEMY_TILE') adjustedType = 'MY_TILE';
        }

        mapData.push({ x, y, tileType: adjustedType, hasDice: false });

        const tile = document.createElement('div');
        tile.id = `tile-${x}-${y}`;
        tile.className = `tile ${adjustedType}`; // 내 화면엔 항상 내 진영이 파란색으로 보임
        
        if (!isBattle) {
            if (type === 'MY_TILE') tile.innerText = "내 타일";
            else if (type === 'ENEMY_TILE') tile.innerText = "적 타일";
            else if (type === 'WALL') tile.innerText = "벽";
        }

        tile.onclick = () => onTileClickForBattle(x, y);
        gridElement.appendChild(tile);
    });
}

// script.js 수정 및 추가
function showHome() {
    // 모든 섹션 숨기기
    document.getElementById('auth-form').style.display = 'none';
    document.getElementById('editor-section').style.display = 'none';
    // 홈 화면 보이기
    document.getElementById('home-screen').style.display = 'block';
    
    const name = (currentUser && currentUser.username) ? currentUser.username : "무명용사";
    document.getElementById('welcome-msg').innerText = `${name}님, 전장에 오신 것을 환영합니다!`;
}

// 메뉴 이동 함수
function navTo(page) {
    // 1. 제어해야 할 모든 섹션과 UI 요소를 포함합니다.
    const allSections = [
        'auth-form', 
        'home-screen', 
        'editor-section', 
        'deck-section', 
        'battle-header', 
        'battle-hand-section'
    ];
    
    // 2. 모든 요소를 숨깁니다.
    allSections.forEach(id => {
        const element = document.getElementById(id);
        if (element) element.style.display = 'none';
    });
    
    // 3. 페이지별 맞춤 화면 설정
    if (page === 'editor') {
        document.getElementById('editor-section').style.display = 'block';
        // 에디터 도구 표시
        document.querySelector('.palette').style.display = 'flex';
        document.querySelector('.actions').style.display = 'block';
        const h2 = document.querySelector('#editor-section h2');
        if (h2) h2.innerText = "🏗️ 맵 빌더";
        initMap(); 
    } 
    else if (page === 'battle') {
        startMatch(); 
    } 
    else if (page === 'deck') {
        // ✅ 무한 루프 방지: 단순히 섹션만 보여줍니다.
        document.getElementById('deck-section').style.display = 'block';
    } 
    else if (page === 'home') {
        document.getElementById('home-screen').style.display = 'block';
    }
}

// handleServerLogin 성공 시 showHome 호출로 변경
async function handleServerLogin(firebaseUser, providedNickname = null) {
    try {
        const response = await fetch(`${SERVER_URL}/api/user/login`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ 
                uid: firebaseUser.uid, 
                username: providedNickname 
            })
        });

        if (response.ok) {
            currentUser = await response.json();
            showHome(); // 에디터 대신 홈으로 이동
        }
    } catch (error) {
        console.error("서버 통신 실패:", error);
    }
}

//주사위 덱 로직
let allDice = [];      // DB에서 가져온 전체 주사위 정보
let selectedDice = []; // 현재 유저가 선택한 덱 (타입명 리스트)

async function showDeckEditor() {
    navTo('deck');
    
    // 1. 유저의 기존 덱 정보 초기화 (DB에서 불러온 값 활용)
    if (currentUser && currentUser.selectedDeck) {
        selectedDice = currentUser.selectedDeck.split(",").filter(d => d !== "");
    } else {
        selectedDice = [];
    }

    try {
        // 2. 전체 주사위 목록 불러오기
        const res = await fetch(`${SERVER_URL}/api/dice/list`);
        allDice = await res.json();

        renderDeckUI();
    } catch (err) {
        console.error("데이터 로드 실패:", err);
    }
}

// 화면을 다시 그리는 핵심 함수
function renderDeckUI() {
    const currentDeckDiv = document.getElementById('current-deck');
    const diceListDiv = document.getElementById('dice-list');
    
    currentDeckDiv.innerHTML = "";
    diceListDiv.innerHTML = "";

    // 1. 하단: 전체 주사위 목록 출력
    allDice.forEach(dice => {
        // 이미 덱에 포함된 주사위는 목록에서 비활성화 효과를 줄 수 있습니다.
        const isSelected = selectedDice.includes(dice.diceType);
        const card = createDiceCard(dice, isSelected);
        
        card.onclick = () => {
            if (isSelected) return alert("이미 덱에 포함되어 있습니다.");
            if (selectedDice.length >= 5) return alert("덱은 최대 5개까지입니다.");
            
            selectedDice.push(dice.diceType);
            renderDeckUI(); // 다시 그리기
        };
        diceListDiv.appendChild(card);
    });

    // 2. 상단: 내 현재 덱 출력
    selectedDice.forEach(type => {
        const diceInfo = allDice.find(d => d.diceType === type);
        if (diceInfo) {
            const card = createDiceCard(diceInfo, false);
            card.classList.add('in-deck');
            card.onclick = () => {
                // 클릭 시 덱에서 제거
                selectedDice = selectedDice.filter(d => d !== type);
                renderDeckUI(); // 다시 그리기
            };
            currentDeckDiv.appendChild(card);
        }
    });
}

// 주사위 카드 HTML 생성 도우미
function createDiceCard(dice, isSelected) {
    const card = document.createElement('div');
    card.className = `dice-card ${isSelected ? 'disabled' : ''}`;
    card.style.borderColor = dice.color;
    card.innerHTML = `
        <div class="dice-icon" style="color:${dice.color}">🎲</div>
        <h4>${dice.name}</h4>
        <p class="dice-desc">${dice.description}</p>
        <div class="dice-stats">공격력:${dice.damage} | 사거리:${dice.range}</div>
    `;
    return card;
}

// 덱을 DB에 저장
async function saveUserDeck() {
    if (selectedDice.length !== 5) return alert("주사위 5개를 모두 골라주세요!");

    const deckString = selectedDice.join(","); // 저장할 덱 문자열

    const res = await fetch(`${SERVER_URL}/api/user/deck/save`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
            uid: currentUser.firebaseUid,
            deck: deckString
        })
    });
    
    if (res.ok) {
        // [핵심] DB 저장 성공 후, 현재 메모리의 유저 정보도 업데이트해줍니다.
        if (currentUser) {
            currentUser.selectedDeck = deckString;
        }
        
        alert("✅ 나만의 덱이 저장되었습니다!");
        renderDeckUI();
    } else {
        alert("❌ 덱 저장에 실패했습니다.");
    }
}

// 전투 매칭 시작 (웹소켓 연결 후 방에 입장)
let currentRoomId = null;

// startMatch 함수
async function startMatch() {
    if (!currentUser) return alert("로그인이 필요합니다.");

    if (!currentUser.selectedDeck || currentUser.selectedDeck.trim() === "" || currentUser.selectedDeck.split(',').filter(d => d).length === 0) {
        alert("덱을 먼저 구성해주세요!");
        return;
    }

    const overlay = document.getElementById('matching-overlay');
    if (overlay) overlay.style.display = 'flex';

    try {
        const res = await fetch(`${SERVER_URL}/api/battle/match?userUid=${currentUser.firebaseUid}`, {
            method: 'POST'
        });

        if (res.status === 200) {
            const data = await res.json();
            currentRoomId = data.roomId;
            
            if (overlay) overlay.style.display = 'none';
            if (matchTimer) clearTimeout(matchTimer);

            // 1. UI 설정 (홈 버튼 숨기기 및 전장 제목 변경)
            const backBtn = document.querySelector('#editor-section .back-btn');
            if (backBtn) backBtn.style.display = 'none';

            document.getElementById('battle-header').style.display = 'flex';
            document.getElementById('battle-hand-section').style.display = 'block';
            
            const editorSection = document.getElementById('editor-section');
            if (editorSection) {
                editorSection.style.display = 'block';
                const h2 = editorSection.querySelector('h2');
                if (h2) h2.innerText = "⚔️ 실시간 전장";
                document.querySelector('.palette').style.display = 'none';
                document.querySelector('.actions').style.display = 'none';
            }

            // 2. 웹소켓 연결 (서버에 READY를 보내고 GAME_START를 기다림)
            connectWebSocket();

            // 3. 맵 데이터 로드 및 전역 변수 저장
            const startRes = await fetch(`${SERVER_URL}/api/battle/start?userUid=${currentUser.firebaseUid}`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(currentUser.selectedDeck.split(","))
            });
            const startData = await startRes.json();

            // 맵 데이터 저장 및 로드 (하지만 visibility: hidden 상태라 안 보임)
            if (startData.mapData && startData.mapData.length > 0) {
                window.currentMapString = startData.mapData[0].mapData;
                loadMapToGrid(window.currentMapString, true);
            }

            // ✅ 대기 알림: 아직 주사위가 나오지 않고 상대방을 기다리는 상태임을 표시합니다.
            document.getElementById('battle-hand').innerHTML = "<h4>⚔️ 상대방을 기다리는 중...</h4>";
            
        } else if (res.status === 202) {
            matchTimer = setTimeout(startMatch, 5000); 
        }
    } catch (err) {
        console.error("매칭 오류:", err);
        cancelMatch();
    }
}

// 매칭 취소 함수
let matchTimer = null;

function cancelMatch() {
    if (matchTimer) {
        clearTimeout(matchTimer);
        matchTimer = null;
    }
    // 서버에도 매칭 취소 알림 보내기
    fetch(`${SERVER_URL}/api/battle/cancel?userUid=${currentUser.firebaseUid}`, {
        method: 'POST'
    });
    const overlay = document.getElementById('matching-overlay');
    if (overlay) overlay.style.display = 'none';
    
    navTo('home');
}

// 변수 추가
let placementCount = 0;
let isSecondPlayer = false; // 맵 반전용

// 1. 웹소켓 연결 (개인 채널 구독 필수)
function connectWebSocket() {
    if (!currentRoomId || !currentUser) return;
    const socket = new SockJS(`${SERVER_URL}/ws?userUid=${currentUser.firebaseUid}`);
    stompClient = Stomp.over(socket);

    stompClient.connect({}, function (frame) {
        console.log("웹소켓 연결 성공");
        
        // 공통 채널
        stompClient.subscribe(`/topic/battle/${currentRoomId}`, function (message) {
            handleBattleMessage(JSON.parse(message.body));
        });

        // ✅ [추가] 개인 채널 (리필 및 시작 신호 수신)
        stompClient.subscribe(`/topic/battle/${currentRoomId}/${currentUser.firebaseUid}`, function (message) {
            handleBattleMessage(JSON.parse(message.body));
        });

        // 준비 완료 신호 전송
        stompClient.send(`/app/battle/${currentRoomId}/ready`, {}, JSON.stringify({
            type: "READY",
            sender: currentUser.firebaseUid
        }));
    });
}

//주사위 보여지는거 관리
let myHand = []; // 서버에서 받은 내 주사위 리스트
let selectedDiceFromHand = null; // 내가 배치하려고 선택한 주사위

// 서버에서 받은 손패(주사위 2개)를 화면에 그리는 함수
function renderHand() {
    const handDiv = document.getElementById('battle-hand'); // index.html의 손패 영역
    if (!handDiv) return;

    handDiv.innerHTML = ""; // 기존 손패 초기화

    myHand.forEach(diceType => {
        // 전체 주사위 데이터(allDice)에서 해당 타입의 정보를 찾음
        const diceInfo = allDice.find(d => d.diceType === diceType);
        
        if (diceInfo) {
            const card = document.createElement('div');
            card.className = 'dice-card';
            card.style.borderColor = diceInfo.color;
            card.innerHTML = `
                <div class="dice-icon" style="color:${diceInfo.color}">${getDiceEmoji(diceType)}</div>
                <h4>${diceInfo.name}</h4>
                <div class="dice-stats">공격:${diceInfo.damage} | 사거리:${diceInfo.range}</div>
            `;

            // 주사위 클릭 시 선택 효과
            card.onclick = () => {
                document.querySelectorAll('#battle-hand .dice-card').forEach(c => c.classList.remove('selected'));
                card.classList.add('selected');
                selectedDiceFromHand = diceType; // 배치할 주사위로 설정
            };

            handDiv.appendChild(card);
        }
    });
}


// 주사위 배치 및 자동 완료 로직
function onTileClickForBattle(x, y) {
    // 3회 제한
    if (placementCount >= 3) return;
    if (!selectedDiceFromHand) return;

    // 타일 유효성 체크
    const tileInfo = mapData.find(t => t.x === x && t.y === y);
    const tileEl = document.getElementById(`tile-${x}-${y}`);
    
    // 내 타일이고, 비어있을 때만
    if (!tileInfo || tileInfo.tileType !== 'MY_TILE' || tileInfo.hasDice) {
        return; 
    }

    // 서버 전송
    const payload = {
        type: "PLACE",
        sender: currentUser.firebaseUid,
        x: x, y: y,
        diceType: selectedDiceFromHand,
        turn: currentTurn
    };
    stompClient.send(`/app/battle/${currentRoomId}/place`, {}, JSON.stringify(payload));
    
    // UI 즉시 반영 (내가 놓은 것)
    tileEl.innerText = getDiceEmoji(selectedDiceFromHand);
    tileEl.classList.add('placed-dice');
    tileEl.setAttribute('data-dice', selectedDiceFromHand); // 타입 저장
    tileInfo.hasDice = true;
    
    // 손패 처리
    myHand = myHand.filter(d => d !== selectedDiceFromHand); 
    selectedDiceFromHand = null;
    placementCount++;
    
    // ✅ 3개를 모두 놓았다면 -> 자동 완료 처리
    if (placementCount >= 3) {
        sendCompleteSignal(); // 서버에 COMPLETE 전송
        
        // UI 숨김 및 대기 문구
        document.getElementById('battle-hand-section').style.display = 'none';
        document.getElementById('battle-hand').innerHTML = ""; // 초기화
        
        // 맵 상단 등에 대기 상태 표시 (선택)
        const timerDiv = document.getElementById('battle-timer');
        if(timerDiv) timerDiv.innerText = "상대 대기 중...";
    } else {
        renderHand(); // 아직 남았으면 손패 다시 그리기
    }
}

//현재 턴 정의
let currentTurn = 1;

// 메시지 처리 (전투 연출 포함)
function handleBattleMessage(data) {
    switch(data.type) {
        case "GAME_START":
            isSecondPlayer = (data.sender === "1"); 
            currentTurn = 1;
            if (window.currentMapString) loadMapToGrid(window.currentMapString, true);
            document.getElementById('map-grid').style.visibility = 'visible';
            
            myHand = data.nextHand;
            placementCount = 0;
            renderHand();
            startBattleTimer();
            break;

        case "DICE_REFILL":
            // 3개 다 놓기 전까지만 리필 유효
            if (placementCount < 3) {
                myHand = data.nextHand;
                renderHand();
            }
            break;
            
        case "TURN_PROGRESS":
            startNextRound(data.nextHand, data.turn);
            break;

        case "REVEAL":
            if (battleTimer) clearInterval(battleTimer);
            
            // 1. 맵 전체 공개 및 체력바 세팅 (전투 모드)
            renderFullMap(data.allPlacements, true); 
            
            // 2. UI: 전투 중 표시
            document.getElementById('battle-hand-section').style.display = 'block';
            document.getElementById('battle-hand').innerHTML = `
                <div style="text-align: center; color: #e74c3c;">
                    <h3>🔥 전투 진행 중... <span id="combat-countdown">30</span></h3>
                </div>`;

            // 3. 전투 로그 재생
            if (data.combatLogs) {
                playCombatLogs(data.combatLogs);
            }

            // 4. 카운트다운
            let combatTime = 30;
            const combatInterval = setInterval(() => {
                combatTime--;
                const counter = document.getElementById('combat-countdown');
                if(counter) counter.innerText = combatTime;
                if(combatTime <= 0) clearInterval(combatInterval);
            }, 1000);

            // 5. 30초 후 결과 반영 및 다음 라운드
            setTimeout(() => {
                clearInterval(combatInterval);
                
                // ✅ 최종 체력 동기화 (서버 값 사용)
                myHp = data.remainingMyHp;
                enemyHp = data.remainingEnemyHp;
                updateHpUI('my-hp', myHp);
                updateHpUI('enemy-hp', enemyHp);

                if (data.loserUid && data.loserUid !== "NONE") {
                    alert(data.loserUid === currentUser.firebaseUid ? "패배했습니다..." : "승리했습니다!");
                    navTo('home');
                } else {
                    startNextRound(data.nextHand, currentTurn + 1); // 다음 라운드
                }
            }, 30000);
            break;
            
        case "WAIT_OPPONENT":
            // 상대방 기다리는 중... (UI 표시)
            break;
    }
}

// 전투 로그 재생 (투사체 & 체력바)
function playCombatLogs(logs) {
    logs.forEach(log => {
        setTimeout(() => {
            // ✅ [수정] 공격자 타일을 가져와서 현재 죽은 상태인지 확인
            const attackerTile = document.getElementById(`tile-${log.attackerX}-${log.attackerY}`);
            if (attackerTile && attackerTile.classList.contains('dead')) {
                return; // 이미 죽었다면 공격(투사체 발사)을 취소합니다.
            }
            animateProjectile(log.attackerX, log.attackerY, log.targetX, log.targetY, log.attackType);
            
            setTimeout(() => {
                updateUnitHp(log.targetX, log.targetY, log.damage);
            }, 300);
        }, log.timeDelay);
    });
}
// 투사체 애니메이션 (좌표 계산)
function animateProjectile(sx, sy, tx, ty, type) {
    const startTile = document.getElementById(`tile-${sx}-${sy}`);
    const endTile = document.getElementById(`tile-${tx}-${ty}`);
    if (!startTile || !endTile) return;

    const ball = document.createElement('div');
    ball.className = 'projectile';
    
    // 공격 타입별 색상
    if (type.includes('FIRE')) ball.style.backgroundColor = '#e74c3c';
    else if (type.includes('WIND')) ball.style.backgroundColor = '#3498db';
    else if (type.includes('ELECTRIC')) ball.style.backgroundColor = '#f1c40f';
    
    // 시작 위치 (화면 기준 절대 좌표 계산)
    const sRect = startTile.getBoundingClientRect();
    const eRect = endTile.getBoundingClientRect();
    
    // body에 붙여서 좌표 제약 없이 이동
    document.body.appendChild(ball);
    
    // 초기 위치 설정 (+24는 타일 중심 보정값)
    ball.style.left = (sRect.left + 24) + 'px';
    ball.style.top = (sRect.top + 24) + 'px';
    
    // 강제 리플로우 (애니메이션 트리거)
    ball.getBoundingClientRect(); 
    
    // 목표 위치로 이동
    const deltaX = eRect.left - sRect.left;
    const deltaY = eRect.top - sRect.top;
    ball.style.transform = `translate(${deltaX}px, ${deltaY}px)`;
    
    // 도착 후 제거
    setTimeout(() => ball.remove(), 300);
}

// 유닛 체력바 업데이트 및 사망 처리
function updateUnitHp(x, y, damage) {
    const tile = document.getElementById(`tile-${x}-${y}`);
    if (!tile) return;
    
    let currentHp = parseInt(tile.getAttribute('data-hp') || 100);
    const maxHp = parseInt(tile.getAttribute('data-max-hp') || 100);
    
    currentHp -= damage;
    tile.setAttribute('data-hp', currentHp);
    
    const fill = tile.querySelector('.hp-bar-fill');
    if (fill) {
        const percent = Math.max(0, (currentHp / maxHp) * 100);
        fill.style.width = `${percent}%`;
    }
    
    if (currentHp <= 0) {
        tile.classList.add('dead'); // 회색 처리
    }
}

// 서버에 배치를 마쳤음을 알리는 확정 신호 함수
function sendCompleteSignal() {
    if (!stompClient || !currentRoomId) return;

    // 서버(BattleService)에 COMPLETE 타입으로 메시지 전송
    stompClient.send(`/app/battle/${currentRoomId}/place`, {}, JSON.stringify({
        type: "COMPLETE",
        sender: currentUser.firebaseUid,
        turn: currentTurn
    }));
    
    // 추가 배치 방지를 위해 UI 숨김
    document.getElementById('battle-hand-section').style.display = 'none';
    console.log("이번 턴 배치를 확정했습니다. 상대방을 기다리는 중...");
}

// 타이머 표시
let battleTimer = null;
let timeLeft = 60;

function startBattleTimer() {
    if (battleTimer) clearInterval(battleTimer);
    timeLeft = 60;
    updateTimerUI(); // ✅ 시작 즉시 UI 갱신

    battleTimer = setInterval(() => {
        timeLeft--;
        updateTimerUI(); // ✅ 매 초마다 UI 갱신

        if (timeLeft <= 0) {
            clearInterval(battleTimer);
            sendCompleteSignal(); // ✅ 시간 종료 시 자동 확정
        }
    }, 1000);
}

function updateTimerUI() {
    const timerContainer = document.getElementById('battle-timer-container');
    const timerEl = document.getElementById('battle-timer');
    if (timerContainer) timerContainer.style.display = 'block'; // 타이머 보이기
    if (timerEl) timerEl.innerText = `남은 시간: ${timeLeft}초`;
}

// renderFullMap 보완: 체력바 추가
function renderFullMap(placements, isBattleMode) {
    if (!placements) return;
    placements.forEach(p => {
        const tile = document.getElementById(`tile-${p.x}-${p.y}`);
        if (tile) {
            tile.innerText = getDiceEmoji(p.diceType);
            tile.classList.add('placed-dice');
            
            // 진영 색상
            const tileInfo = mapData.find(m => m.x === p.x && m.y === p.y);
            if (tileInfo) {
                if (tileInfo.tileType === 'MY_TILE') tile.style.backgroundColor = "#3498db";
                else if (tileInfo.tileType === 'ENEMY_TILE') tile.style.backgroundColor = "#e74c3c";
            }

            if (isBattleMode) {
                // 체력바 주입
                if (!tile.querySelector('.hp-bar-container')) {
                    tile.setAttribute('data-hp', 100); // DB 연동 시 p.hp 사용
                    tile.setAttribute('data-max-hp', 100);
                    const bar = document.createElement('div');
                    bar.className = 'hp-bar-container';
                    bar.innerHTML = '<div class="hp-bar-fill"></div>';
                    tile.appendChild(bar);
                }
            }
        }
    });
}

let myHp = 5;
let enemyHp = 5;

// 주사위 타입에 따른 이모지 반환 (선택 사항)
function getDiceEmoji(type) {
    const emojis = {
        'FIRE': '🔥',
        'WIND': '🌪️',
        'ELECTRIC': '⚡',
        'SWORD': '⚔️',
        'SNIPER': '🎯'
    };
    return emojis[type] || "🎲";
}

function applyDamage(loserUid) {
    // 1. 무승부 판정
    if (loserUid === "NONE") {
        resetForNextRound(); // 체력 깎지 않고 다음 라운드 준비
        return;
    }

    // 2. 패배자 체력 차감
    if (loserUid === currentUser.firebaseUid) {
        myHp--;
        updateHpUI('my-hp', myHp);
    } else {
        enemyHp--;
        updateHpUI('enemy-hp', enemyHp);
    }

    // 3. 최종 승패 확인 후 라운드 초기화
    checkGameOver();
    resetForNextRound();
}

function updateHpUI(elementId, hp) {
    const hpBar = document.getElementById(elementId);
    hpBar.innerText = "❤️".repeat(hp) + "🖤".repeat(5 - hp);
}

// 라운드 리셋 및 복구
function startNextRound(nextHand, nextTurnVal) {
    currentTurn = nextTurnVal;
    placementCount = 0;
    selectedDiceFromHand = null;
    myHand = nextHand || [];
    
    // 맵 상태 복구 (체력바 리셋, 사망 해제)
    // renderFullMap은 기존 배치 정보로 덮어씌우므로 자동으로 초기화됨
    // 다만 시각적으로 깔끔하게 하기 위해 한 번 클리어하고 그리는 것도 방법
    // 여기선 UI만 갱신
    document.getElementById('battle-hand-section').style.display = 'block';
    renderHand();
    startBattleTimer(); // 60초 시작
    
    // 맵의 모든 유닛을 '풀피' 상태로 시각적 복구 (기존 배치 유지 시)
    document.querySelectorAll('.tile.placed-dice').forEach(tile => {
        tile.classList.remove('dead');
        tile.setAttribute('data-hp', 100); // 임시값, 실제론 DB값
        const fill = tile.querySelector('.hp-bar-fill');
        if(fill) fill.style.width = '100%';
    });
}

window.addEventListener('DOMContentLoaded', () => {
    //초기화 실행
    setupFirebase();
});
