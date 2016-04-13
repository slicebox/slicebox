(function () {
   'use strict';
}());

angular.module('slicebox.import', ['ngRoute', 'ngFileUpload'])

.config(function($routeProvider) {
  $routeProvider.when('/import', {
    templateUrl: '/assets/partials/import.html',
    controller: 'ImportCtrl'
  });  
})

.controller('ImportCtrl', function($scope, Upload, $q, $interval, sbxToast, openAddEntityModal, openDeleteEntitiesModalFunction, openTagSeriesModalFunction) {
    
    $scope.sessionActions =
        [
            {
                name: 'Delete',
                action: openDeleteEntitiesModalFunction('/api/imports/', 'import sessions')
            },
            {
                name: 'Tag Series',
                action: openTagSeriesModalFunction('/api/imports/')
            }
        ];

    $scope.uiState.selectedSession = null;
    $scope.uiState.currentFileSet = {
        processing: false,
        index: 0,
        total: 0,
        progress: 0
    };

    $scope.callbacks = {};

    var timer = $interval(function() {
        if ($scope.uiState.currentFileSet.processing) {
            $scope.callbacks.importSessionsTable.reloadPage();
        }
    }, 3000);

    $scope.$on('$destroy', function() {
        $interval.cancel(timer);
    });
  
    $scope.loadImportSessions = function(startIndex, count) {
        var sessionsPromise = $http.get('/api/importing/sessions');
        return sessionsPromise;
    };

    $scope.addImportSessionButtonClicked = function() {
        openAddEntityModal(
            'addImportSessionModalContent.html', 
            'AddImportSessionModalCtrl', 
            '/api/imports', 
            'Import session', 
            $scope.callbacks.importSessionsTable)
        .then(function (importSession) {
            $http.post('/api/importing/sessions', importSession).then(function(data) {
                $scope.callbacks.importSessionsTable.selectObject(data.importSession);
            })
        });
    };

    $scope.importSessionSelected = function(importSession) {
        $scope.uiState.selectedSession = importSession;
    };

    function importFirst(files) {
        if (files && files.length) {
            $scope.uiState.currentFileSet.index++;
            $scope.uiState.selectedSession.lastUpdated = new Date().getTime(); // TODO move to server
            $scope.uiState.currentFileSet.progress = Math.round(100 * $scope.uiState.currentFileSet.index / $scope.uiState.currentFileSet.total);
            Upload.upload({
                url: '/api/importing/sessions/' + $scope.uiState.selectedSession.id + '/images',
                file: files[0]
            }).success(function (data, status, headers, config) {
                //importedFiles.push({ name: config.file.name });
                files.shift();
                $scope.uiState.selectedSession.filesImported++; // TODO move to server
                if (status == 201) {
                    $scope.uiState.selectedSession.filesAdded++; // TODO move to server
                }
                importFirst(files);
            }).error(function (message, status, headers, config) {
                if (status >= 300 && status !== 400) {
                    sbxToast.showErrorMessage('Error importing file: ' + message);
                }
                files.shift();
                $scope.uiState.selectedSession.filesRejected++; // TODO move to server
                importFirst(files);
            });
        } else {
            $scope.uiState.currentFileSet.processing = false;
            $scope.callbacks.importSessionsTable.reloadPage();
        }
    }

    $scope.import = function(files) {
        $scope.uiState.currentFileSet.processing = true;
        $scope.uiState.currentFileSet.index = 0;
        $scope.uiState.currentFileSet.total = files.length;
        $scope.uiState.currentFileSet.progress = 0;
        importFirst(files);
    };

})

.controller('AddImportSessionModalCtrl', function($scope, $mdDialog) {

    // Scope functions
    $scope.addButtonClicked = function() {
        return $mdDialog.hide({ 
            id: new Date().getTime(), // change to -1 later... 
            name: $scope.name,
            userId: -1,
            user: "user",
            filesImported: 0, 
            filesAdded: 0,
            filesRejected: 0,
            created: new Date().getTime(),
            lastUpdated: new Date().getTime()
        });
    };

    $scope.cancelButtonClicked = function() {
        $mdDialog.cancel();
    };
});