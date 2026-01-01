const GRID_SIZE = 8;
let mapData = []; 
let selectedType = 'MY_TILE';
let currentUser = null;

// 1. 서버 주소 설정 (Render 서버의 실제 External URL을 적어주세요)
const SERVER_URL = "https://autocardbattle.onrender.com";

// 2. 페이지 로드 시 Firebase 초기화 및 인증 상태 확인
async function setupFirebase() {
    try {
        const response = await fetch(`${SERVER_URL}/api/config/firebase`, {
            headers: { 'Origin': window.location.origin }
        });
        const config = await response.json();
        firebase.initializeApp(config);

        // 인증 상태 감시
        firebase.auth().onAuthStateChanged((user) => {
            if (user) {
                handleServerLogin(user);
            } else {
                showLoginForm();
            }
        });
    } catch (error) {
        console.error("서버 로딩 실패패", 버 로딩 실;
    }
}

// 3. 구글 로그인 실행
async function handleLogin() {
    const provider = new firebase.auth.GoogleAuthProvider();
    try {
        await firebase.auth().signInWithPopup(provider);
    } catch (error) {
        alert("로그인에 실패했습니다: " + error.message);
    }
}

// 4. 서버에 유저 정보 등록 및 에디터 진입
async function handleServerLogin(firebaseUser) {
    try {
        const response = await fetch(`${SERVER_URL}/api/user/login`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                uid: firebaseUser.uid,
                username: firebaseUser.displayName
            })
        });

        if (response.ok) {
            currentUser = await response.json();
            showEditor();
        }
    } catch (error) {
        console.error("서버 로그인 실패:", error);
    }
}

function showLoginForm() {
    document.getElementById('login-form').style.display = 'block';
    document.getElementById('editor-section').style.display = 'none';
}

function showEditor() {
    document.getElementById('login-form').style.display = 'none';
    document.getElementById('editor-section').style.display = 'block';
    const userDisplay = document.getElementById('user-display');
    if (userDisplay) userDisplay.innerText = `${currentUser.username}님, 전장을 설계하세요!`;
    initMap(); // 로그인 성공 후에 맵을 생성함
}

function handleLogout() {
    firebase.auth().signOut().then(() => {
        window.location.reload();
    });
}

// 5. 맵 에디터 로직
function initMap() {
    const gridElement = document.getElementById('map-grid');
    if (!gridElement || gridElement.children.length > 0) return; // 중복 생성 방지

    mapData = [];
    for (let y = 0; y < GRID_SIZE; y++) {
        for (let x = 0; x < GRID_SIZE; x++) {
            const tile = document.createElement('div');
            tile.classList.add('tile');
            tile.id = `tile-${x}-${y}`;
            
            // 왼쪽 4칸만 클릭 가능 (대칭 설계)
            if (x < 4) tile.onclick = () => handleTileClick(x, y);
            else tile.classList.add('symmetric-zone');
            
            gridElement.appendChild(tile);
            mapData.push({ x, y, tileType: 'EMPTY' });
        }
    }
}

function handleTileClick(x, y) {
    const type = selectedType;
    updateTile(x, y, type);

    // 상대 영역 대칭 및 치환 (Mirror & Swap)
    const symX = 7 - x;
    let symType = type;
    if (type === 'MY_TILE') symType = 'ENEMY_TILE';
    else if (type === 'ENEMY_TILE') symType = 'MY_TILE';

    updateTile(symX, y, symType);
}

function updateTile(x, y, type) {
    const tileObj = mapData.find(t => t.x === x && t.y === y);
    tileObj.tileType = type;
    const el = document.getElementById(`tile-${x}-${y}`);
    el.className = `tile ${type} ${x >= 4 ? 'symmetric-zone' : ''}`;
    
    // 타일 텍스트 표시
    let text = '';
    if (type === 'MY_TILE') text = '내꺼';
    else if (type === 'ENEMY_TILE') text = '적꺼';
    else if (type === 'WALL') text = '벽';
    el.innerText = text;
}

function selectType(type, e) {
    selectedType = type;
    document.querySelectorAll('.tool-btn').forEach(b => b.classList.remove('active'));
    e.target.classList.add('active');
}

// 6. 맵 저장 로직 (중복 체크 응답 처리 포함)
async function saveMap() {
    if (!currentUser) {
        alert("로그인이 필요합니다.");
        return;
    }

    const halfMap = mapData.filter(t => t.x < 4);
    
    try {
        const response = await fetch(`${SERVER_URL}/api/map/save`, {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify(halfMap)
        });
        
        const result = await response.text();
        if (response.ok) {
            alert(result);
        } else {
            alert("저장 실패: " + result); // "이미 동일한 구조의 맵이 존재합니다." 등
        }
    } catch (error) {
        alert("서버 연결에 실패했습니다.");
    }
}

// 실행 시작
setupFirebase();
