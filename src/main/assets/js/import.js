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

.controller('ImportCtrl', function($scope, $http, Upload, $q, $interval, sbxToast, openAddEntityModal, openDeleteEntitiesModalFunction, openTagSeriesModalFunction) {

    $scope.sessionActions =
        [
            {
                name: 'Delete',
                action: openDeleteEntitiesModalFunction('/api/import/sessions/', 'import sessions')
            },
            {
                name: 'Tag Series',
                action: openTagSeriesModalFunction('/api/import/sessions/')
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
        var sessionsPromise = $http.get('/api/import/sessions?startindex=' + startIndex + '&count=' + count);
        return sessionsPromise;
    };

    $scope.addImportSessionButtonClicked = function() {
        openAddEntityModal(
            'addImportSessionModalContent.html',
            'AddImportSessionModalCtrl',
            '/api/import/sessions/',
            'Import session',
            $scope.callbacks.importSessionsTable)
        .then(function (importSession) {
            $scope.callbacks.importSessionsTable.selectObject(importSession);
        });
    };

    $scope.importSessionSelected = function(importSession) {
        $scope.uiState.selectedSession = importSession;
    };

    function importFirst(files) {
        if (files && files.length) {
            $scope.uiState.currentFileSet.index++;
            $scope.uiState.currentFileSet.progress = Math.round(100 * $scope.uiState.currentFileSet.index / $scope.uiState.currentFileSet.total);
            Upload.upload({
                url: '/api/import/sessions/' + $scope.uiState.selectedSession.id + '/images',
                file: files[0]
            }).success(function (data, status, headers, config) {
                //importedFiles.push({ name: config.file.name });
                files.shift();
                importFirst(files);
            }).error(function (message, status, headers, config) {
                if (status >= 300 && status !== 400) {
                    sbxToast.showErrorMessage('Error importing file: ' + message);
                }
                files.shift();
                importFirst(files);
            });
        } else {
            $scope.uiState.currentFileSet.processing = false;
            $scope.callbacks.importSessionsTable.reloadPage();
        }
    }

    $scope.import = function(files) {
        var filesPrune = [];
        for (var i = 0; i < files.length; i++) {
            if (files[i].type !== 'directory') {
                filesPrune.push(files[i]);
            }
        }
        $scope.uiState.currentFileSet.processing = true;
        $scope.uiState.currentFileSet.index = 0;
        $scope.uiState.currentFileSet.total = filesPrune.length;
        $scope.uiState.currentFileSet.progress = 0;
        importFirst(filesPrune);
    };

})

.controller('AddImportSessionModalCtrl', function($scope, $mdDialog) {

    // Scope functions
    $scope.addButtonClicked = function() {
        return $mdDialog.hide({
            id: -1,
            name: $scope.name,
            userId: -1,
            user: "",
            filesImported: 0,
            filesAdded: 0,
            filesRejected: 0,
            created: 0,
            lastUpdated: 0
        });
    };

    $scope.cancelButtonClicked = function() {
        $mdDialog.cancel();
    };
});